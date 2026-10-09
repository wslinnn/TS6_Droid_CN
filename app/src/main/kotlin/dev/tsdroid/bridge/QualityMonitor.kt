package dev.tsdroid.bridge

import dev.tsdroid.data.AppLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One quality snapshot as shown in the server info sheet. */
data class QualitySnapshot(
    /** Median round trip time over the recent window, milliseconds. */
    val rttMedianMs: Double,
    /** 95th percentile round trip time — voice is sensitive to tail jitter. */
    val rttP95Ms: Double,
    /** Round trip time jitter (smoothed deviation) in milliseconds. */
    val rttDevMs: Double,
    /** Packet loss fraction (0.0–1.0) over the recent loss window. */
    val packetLoss: Float,
    /** Received bytes per second (latest sample). */
    val bytesReceivedPerSec: Long,
    /** Sent bytes per second (latest sample). */
    val bytesSentPerSec: Long,
)

/**
 * Samples the native connection stats every few seconds, keeps a recent
 * window, and feeds observed loss into the audio bridge so the Opus encoder
 * adapts (FEC redundancy / bitrate) to the network instead of failing on it.
 *
 * The native loss number is cumulative since connect — slow to move and
 * eventually insensitive on long sessions. The monitor differences the
 * cumulative lost/observed counts into a recent-window loss (last ~5s),
 * which matches the desktop client's live-reading behaviour; the same
 * windowed value drives the FEC adaptation.
 *
 * Outage handling: a healthy connection always receives keepalive packets
 * every second, so a streak of zero-rx samples means the network is dead
 * (state stays CONNECTED until the native timeout). During an outage the
 * window is cleared and sampling pauses; after traffic resumes the first
 * samples are discarded because the recovery pong pollutes rtt/rtt_dev with
 * a huge spike.
 */
class QualityMonitor(
    private val tsClient: TsClient,
    private val audioBridge: AudioBridge?,
    /** Invoked once when the link is declared dead, so the service can force a reconnect. */
    private val onLinkDead: (() -> Unit)? = null,
) {
    companion object {
        private const val TAG = "QualityMonitor"
        private const val SAMPLE_INTERVAL_MS = 3_000L

        /** Window length: ~60s of RTT samples at the 3s interval. */
        private const val RTT_WINDOW_SIZE = 20

        /**
         * Loss window: ~5s. Each sample's delta covers the 3s since the
         * previous one, so two deltas span 3–6s depending on phase.
         */
        private const val LOSS_WINDOW_SIZE = 2

        /** Minimum packets in the loss window before it is trusted. */
        private const val MIN_WINDOW_OBSERVED = 5

        /** Consecutive zero-rx samples before the connection counts as dead. */
        private const val DEAD_STREAK = 3

        /** Samples dropped after traffic resumes (recovery transient). */
        private const val RECOVERY_DISCARD = 2
    }

    private val _snapshot = MutableStateFlow<QualitySnapshot?>(null)
    val snapshot: StateFlow<QualitySnapshot?> = _snapshot.asStateFlow()

    private val rttWindow = ArrayDeque<Double>(RTT_WINDOW_SIZE)

    private class LossSample(val observed: Long, val lost: Long)
    private val lossWindow = ArrayDeque<LossSample>(LOSS_WINDOW_SIZE)

    // Cumulative counters from the previous sample, for differencing
    private var prevObserved = -1L
    private var prevLost = 0L

    private var zeroRxStreak = 0
    private var dead = false
    private var recoveryDiscards = 0
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            Log.i(TAG, "Quality sampling started")
            while (isActive) {
                // One bad sample (e.g. a native hiccup during reconnect) must
                // not kill the loop — that would freeze every panel value
                try {
                    sample()
                } catch (e: Exception) {
                    Log.w(TAG, "Quality sample failed", e)
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        resetWindow()
        resetLinkState()
    }

    private fun resetWindow() {
        rttWindow.clear()
        lossWindow.clear()
        prevObserved = -1L
        prevLost = 0L
        _snapshot.value = null
    }

    private fun resetLinkState() {
        zeroRxStreak = 0
        dead = false
        recoveryDiscards = 0
    }

    private suspend fun sample() {
        val stats = tsClient.getNetworkStats() ?: run {
            // Not connected — reset so a stale window can't outlive the session
            if (_snapshot.value != null) resetWindow()
            return
        }

        val rtt = stats[0]
        val lossFraction = stats[2]
        val rxBytes = stats[4].toLong()
        val observed = stats[6].toLong()

        // Zero rx for a streak means no keepalives are coming back — the
        // link is dead even though the state still says connected
        if (rxBytes == 0L) {
            zeroRxStreak++
            if (zeroRxStreak >= DEAD_STREAK && !dead) {
                Log.w(TAG, "No inbound traffic for ${zeroRxStreak * SAMPLE_INTERVAL_MS / 1000}s — treating link as dead")
                // Reset only the windows; the link state must survive so the
                // recovery path below can run when traffic returns
                dead = true
                resetWindow()
                onLinkDead?.invoke()
                return
            }
            if (dead) return
        } else {
            zeroRxStreak = 0
            if (dead) {
                // Traffic resumed: drop the recovery transient samples before
                // letting them pollute rtt/jitter/p95
                recoveryDiscards++
                if (recoveryDiscards <= RECOVERY_DISCARD) {
                    Log.i(TAG, "Link recovered; discarding transient sample $recoveryDiscards/$RECOVERY_DISCARD")
                    return
                }
                Log.i(TAG, "Link recovered; sampling resumed")
                dead = false
                recoveryDiscards = 0
            }
        }

        rttWindow.addLast(rtt)
        while (rttWindow.size > RTT_WINDOW_SIZE) rttWindow.removeFirst()

        // Difference the cumulative lost/observed counts into a recent window:
        // Δlost = loss₂·observed₂ − loss₁·observed₁ (exact, same denominator)
        if (prevObserved in 0 until observed) {
            val lost = Math.round(lossFraction * observed)
            val deltaObserved = observed - prevObserved
            val deltaLost = (lost - prevLost).coerceAtLeast(0)
            lossWindow.addLast(LossSample(deltaObserved, deltaLost))
            while (lossWindow.size > LOSS_WINDOW_SIZE) lossWindow.removeFirst()
        }
        prevObserved = observed
        prevLost = Math.round(lossFraction * observed)

        var windowObserved = 0L
        var windowLost = 0L
        for (s in lossWindow) {
            windowObserved += s.observed
            windowLost += s.lost
        }
        // Fall back to the cumulative fraction until the window has enough
        // observations to be meaningful (an idle connection only exchanges
        // a few keepalive packets per window)
        val windowLoss = if (windowObserved >= MIN_WINDOW_OBSERVED) {
            windowLost.toFloat() / windowObserved
        } else {
            lossFraction.toFloat()
        }

        val sorted = rttWindow.sorted()
        _snapshot.value = QualitySnapshot(
            rttMedianMs = percentile(sorted, 0.5),
            rttP95Ms = percentile(sorted, 0.95),
            rttDevMs = stats[1],
            packetLoss = windowLoss,
            bytesReceivedPerSec = rxBytes,
            bytesSentPerSec = stats[5].toLong(),
        )
        audioBridge?.applyNetworkQuality(windowLoss)
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
