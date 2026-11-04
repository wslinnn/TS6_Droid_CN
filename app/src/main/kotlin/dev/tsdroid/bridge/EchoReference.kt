package dev.tsdroid.bridge

/**
 * Playback→capture reference ring for the software AEC.
 *
 * The echo canceller needs, for every captured frame, the frame of playback
 * PCM that the speaker was emitting at (roughly) that moment. Two problems
 * make this non-trivial:
 *
 * - **Latency window**: the adaptive filter represents echo delays in
 *   `[alignSamples, alignSamples + tail)` only (the tail is set by the
 *   engine). The real write→mic delay on this pipeline is roughly
 *   `AudioTrack buffer (5–85 ms) + acoustic path (~5 ms)` ≈ 10–90 ms, so
 *   [alignSamples] must not exceed the **smallest** plausible delay —
 *   otherwise the direct-path echo falls before the window and cannot be
 *   cancelled at all (measured: 40 ms delay with a 100 ms alignment left the
 *   direct echo untouched, ~7.5 dB ERLE vs 18.5 dB at 0 ms alignment).
 * - **Idle playback**: the playback loop writes nothing while the remote side
 *   is silent. Wall-clock still advances, so [push] fills the gap with zeros
 *   (the speaker emitted silence) — otherwise the reference timeline would
 *   fall behind forever after the first talkspurt pause.
 *
 * Drift correction is deliberately bounded (pipeline-level, fully static):
 * a stale
 * reference skips forward at most to [maxLagSamples]; when the capture stream
 * outruns the reference (idle playback) the read yields zeros, and the first
 * read after the stream advances again re-aligns to the newest frame. All
 * events are counted for the 30 s diagnostics instead of silently reshaping
 * the timeline.
 *
 * Threading: [push] runs on the playback thread, [read] on the capture
 * thread, [reset] on the caller thread — all synchronized (copies of one
 * 960-sample frame, no lock held across device calls).
 */
internal class EchoReference(
    private val sampleRate: Int = AudioBridge.SAMPLE_RATE,
    private val frameSamples: Int = sampleRate / 50, // 20 ms
    private val capacitySamples: Int = sampleRate / 2, // 500 ms
    private val alignSamples: Int = 0, // 0 ms: window starts at the direct path
    private val maxLagSamples: Int = sampleRate * 3 / 10, // 300 ms stale bound
) {
    private val ring = ShortArray(capacitySamples)
    private var writeIndex = 0
    private var totalWritten = 0L

    /** Stream index of the next sample handed to the engine; MIN = unarmed. */
    private var consumePos = Long.MIN_VALUE
    private var lastPushAtMs = 0L

    /** [totalWritten] seen by the previous read — detects an idle stream. */
    private var lastReadTotal = -1L

    private var padFrames = 0
    private var skipFrames = 0
    private var resyncs = 0

    /** Drop every buffered sample — new capture session or route change. */
    @Synchronized
    fun reset() {
        writeIndex = 0
        totalWritten = 0
        consumePos = Long.MIN_VALUE
        lastPushAtMs = 0
        lastReadTotal = -1L
        padFrames = 0
        skipFrames = 0
        resyncs = 0
    }

    /**
     * Append one playback mix frame (post-gain, exactly what was written to
     * AudioTrack). [nowMs] must be a monotonic clock; the gap since the
     * previous push is represented as zero reference frames.
     */
    @Synchronized
    fun push(frame: ShortArray, nowMs: Long) {
        require(frame.size == frameSamples) {
            "reference frame size ${frame.size} != $frameSamples"
        }
        if (lastPushAtMs != 0L) {
            val elapsed = nowMs - lastPushAtMs
            if (elapsed > frameMs) {
                val missing = ((elapsed * sampleRate / 1000) / frameSamples - 1)
                    .coerceIn(0L, (capacitySamples / frameSamples).toLong())
                repeat(missing.toInt()) { appendZeros() }
            }
        }
        append(frame)
        lastPushAtMs = nowMs
        if (consumePos == Long.MIN_VALUE) {
            // Arm alignment: frame i is paired with the playback stream
            // starting at i*frame - align.
            consumePos = totalWritten - alignSamples - frameSamples
        }
    }

    /**
     * Fill [out] with the aligned reference frame for the current capture
     * frame. Returns false when the stream could not supply it (engine still
     * gets zeros, which is the correct reference for "speaker was silent").
     */
    @Synchronized
    fun read(out: ShortArray): Boolean {
        require(out.size == frameSamples) {
            "reference frame size ${out.size} != $frameSamples"
        }
        if (consumePos == Long.MIN_VALUE) {
            out.fill(0)
            return false
        }
        if (consumePos + frameSamples > totalWritten) {
            if (totalWritten == lastReadTotal) {
                // Stream idle: playback loop wrote nothing, so the speaker
                // emitted silence. Re-reading the last played frame here would
                // feed a phantom reference; pad zeros and advance the clock.
                out.fill(0)
                consumePos += frameSamples
                padFrames++
                return false
            }
            // Stream advanced again (playback resumed, possibly after a gap
            // the push-side zero-fill could not cover): re-align to the
            // newest frame instead of staying behind forever.
            consumePos = totalWritten - alignSamples - frameSamples
            resyncs++
        }
        if (consumePos < 0 || consumePos + frameSamples > totalWritten) {
            out.fill(0)
            consumePos += frameSamples
            padFrames++
            lastReadTotal = totalWritten
            return false
        }
        // Stale reference (clock skew, producer burst): skip forward, bounded.
        val behind = totalWritten - consumePos
        if (behind > maxLagSamples) {
            consumePos += behind - maxLagSamples
            skipFrames++
        }
        var idx = (writeIndex - (totalWritten - consumePos).toInt() + capacitySamples * 2) %
            capacitySamples
        for (i in 0 until frameSamples) {
            out[i] = ring[idx]
            idx++
            if (idx == capacitySamples) idx = 0
        }
        consumePos += frameSamples
        lastReadTotal = totalWritten
        return true
    }

    /** Diagnostics snapshot for the 30 s log line; zeroes the counters. */
    @Synchronized
    fun snapshotAndReset(): RefStats {
        val stats = RefStats(padFrames, skipFrames, resyncs)
        padFrames = 0
        skipFrames = 0
        resyncs = 0
        return stats
    }

    private fun append(frame: ShortArray) {
        for (s in frame) {
            ring[writeIndex] = s
            writeIndex++
            if (writeIndex == capacitySamples) writeIndex = 0
        }
        totalWritten += frame.size
    }

    private fun appendZeros() {
        val zero = ShortArray(frameSamples)
        append(zero)
    }

    private val frameMs: Int
        get() = frameSamples * 1000 / sampleRate
}

/** One 30 s window of reference-FIFO health counters. */
internal class RefStats(
    val padFrames: Int,
    val skipFrames: Int,
    val resyncs: Int,
)
