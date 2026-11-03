package dev.tsdroid.bridge

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Echo-return-loss-enhancement (ERLE) probe.
 *
 * Pairs each local-silent mic frame with the most recent far-end (playback
 * mix) frame energy, split into two buckets:
 *
 * - **all pairs** (`erleAllDb`): every far-active mic frame. With no echo
 *   cancellation active (or a broken one), the mic carries loud echo and
 *   this reading stays low — the "no AEC" fingerprint.
 * - **quiet pairs** (`erleQuietDb`): mic frames at/below [SILENCE_RMS] only.
 *   With cancellation actually working the residual is quiet, so this is the
 *   "working" fingerprint. Loud local speech also lands in the all bucket
 *   (double-talk contaminates it), which is why the quiet bucket exists.
 *
 * Pure aggregation — the caller feeds frames and reads one snapshot per
 * diagnostics flush. No clock, no Android types: JVM-testable, and the
 * acceptance yardstick for the software AEC (echo subtraction and later
 * software NS run entirely in-process).
 */
class EchoProbe {

    companion object {
        /** Mic RMS at/below this counts as a quiet (clean-residual) frame. */
        const val SILENCE_RMS = 150.0

        /** Pairs required per bucket before its ERLE reading is emitted (~0.5s of usable pairs). */
        const val MIN_PAIRED_FRAMES = 25

        /** One far-end mix frame is 20ms of playback. */
        const val FAR_FRAME_MS = 20
    }

    private var farFrameCount = 0
    private var lastFarEnergy = 0.0

    private var pairedFrames = 0
    private var pairedFarEnergy = 0.0
    private var pairedMicEnergy = 0.0

    private var quietFrames = 0
    private var quietFarEnergy = 0.0
    private var quietMicEnergy = 0.0

    /** Feed one far-end mix frame right before it is handed to AudioTrack. */
    fun onFarFrame(frame: ShortArray) {
        farFrameCount++
        lastFarEnergy = frameEnergy(frame)
    }

    /** Feed one raw mic frame. [farActive] says whether the speaker played recently. */
    fun onMicFrame(frame: ShortArray, farActive: Boolean) {
        if (!farActive || farFrameCount == 0) return
        val energy = frameEnergy(frame)
        pairedFrames++
        pairedFarEnergy += lastFarEnergy
        pairedMicEnergy += energy
        if (sqrt(energy / frame.size) <= SILENCE_RMS) {
            quietFrames++
            quietFarEnergy += lastFarEnergy
            quietMicEnergy += energy
        }
    }

    /** One diagnostics-flush worth of readings; zeroes the window. */
    fun snapshotAndReset(): EchoStats {
        val stats = EchoStats(
            farMs = farFrameCount * FAR_FRAME_MS,
            erleAllDb = erle(pairedFrames, pairedFarEnergy, pairedMicEnergy),
            erleQuietDb = erle(quietFrames, quietFarEnergy, quietMicEnergy),
            pairedFrames = pairedFrames,
            quietFrames = quietFrames,
        )
        farFrameCount = 0
        lastFarEnergy = 0.0
        pairedFrames = 0
        pairedFarEnergy = 0.0
        pairedMicEnergy = 0.0
        quietFrames = 0
        quietFarEnergy = 0.0
        quietMicEnergy = 0.0
        return stats
    }

    private fun erle(frames: Int, farEnergy: Double, micEnergy: Double): Double? =
        if (frames >= MIN_PAIRED_FRAMES && micEnergy > 0.0) {
            10.0 * log10(farEnergy / micEnergy)
        } else {
            null
        }

    private fun frameEnergy(frame: ShortArray): Double {
        var energy = 0.0
        for (s in frame) {
            energy += s.toDouble() * s.toDouble()
        }
        return energy
    }
}

/**
 * One flush window of echo observations. An `erle*` value is null when too
 * few pairs accumulated for that bucket — an empty reading is a valid
 * observation (nobody talked far-side), not an error.
 */
class EchoStats(
    val farMs: Int,
    val erleAllDb: Double?,
    val erleQuietDb: Double?,
    val pairedFrames: Int,
    val quietFrames: Int,
)
