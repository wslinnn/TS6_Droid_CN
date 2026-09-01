package dev.tsdroid.bridge

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Microphone operating mode. */
enum class MicMode {
    /** Hold-to-talk: mute overrides while the button is held. */
    PTT,

    /** Voice activation: frames are transmitted while they exceed the threshold. */
    VAD,

    /** Open microphone: everything captured is transmitted while unmuted. */
    OPEN;

    fun toRaw(): String = when (this) {
        PTT -> "ptt"
        VAD -> "vad"
        OPEN -> "open"
    }

    companion object {
        fun from(raw: String?): MicMode = when (raw) {
            "vad" -> VAD
            "open" -> OPEN
            else -> PTT
        }
    }
}

/**
 * Voice-activation state machine for the capture loop.
 *
 * Feed it one PCM frame per call; it returns the frames to transmit (pre-roll
 * first on activation) or null to stay silent. Hysteresis (close threshold
 * below open threshold) prevents chatter near the threshold, a hangover keeps
 * transmitting briefly after the level drops so word tails are not cut, and a
 * pre-roll ring buffer preserves word onsets that happened before activation.
 */
class VadGate(
    private val preRollFrames: Int = DEFAULT_PRE_ROLL_FRAMES,
) {
    /** Level (dBFS) above which transmission activates. */
    @Volatile
    var openThresholdDb: Float = DEFAULT_THRESHOLD_DB

    val isActive: Boolean get() = active

    private var active = false
    private var belowSinceMs = -1L
    private val preRoll = ArrayDeque<ShortArray>()

    /**
     * Feed one frame captured at [nowMs].
     *
     * @return frames to encode and send in order, or null to stay silent
     */
    fun process(frame: ShortArray, nowMs: Long): List<ShortArray>? {
        val db = rmsDb(frame)
        if (!active) {
            if (db < openThresholdDb) {
                preRoll.addLast(frame.copyOf())
                while (preRoll.size > preRollFrames) preRoll.removeFirst()
                return null
            }
            // Activate: flush the pre-roll so the word onset is not clipped
            active = true
            belowSinceMs = -1L
            preRoll.addLast(frame.copyOf())
            while (preRoll.size > preRollFrames) preRoll.removeFirst()
            val out = preRoll.toList()
            preRoll.clear()
            return out
        }
        if (db >= openThresholdDb - HYSTERESIS_DB) {
            belowSinceMs = -1L
            return listOf(frame)
        }
        if (belowSinceMs < 0L) belowSinceMs = nowMs
        if (nowMs - belowSinceMs < HANGOVER_MS) return listOf(frame)
        // Deactivate; this trailing frame seeds the next pre-roll
        active = false
        belowSinceMs = -1L
        preRoll.clear()
        preRoll.addLast(frame.copyOf())
        return null
    }

    fun reset() {
        active = false
        belowSinceMs = -1L
        preRoll.clear()
    }

    companion object {
        /** Matches the old hardcoded indicator threshold (rms > 150 ≈ -46.8 dBFS). */
        const val DEFAULT_THRESHOLD_DB = -45f
        const val HYSTERESIS_DB = 3f
        const val HANGOVER_MS = 400L
        const val DEFAULT_PRE_ROLL_FRAMES = 15 // 300 ms at 20 ms frames

        fun dbToGain(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

        fun rmsDb(frame: ShortArray): Float {
            var energy = 0.0
            for (s in frame) {
                val v = s.toDouble()
                energy += v * v
            }
            val rms = sqrt(energy / frame.size)
            if (rms <= 0.0) return -120f
            return (20.0 * log10(rms / 32768.0)).toFloat()
        }
    }
}
