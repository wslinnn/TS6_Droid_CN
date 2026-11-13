package dev.tsdroid.bridge

/**
 * Summing mixer and peak limiter for the playback path.
 *
 * Mixing several decoded voices is an addition, and every extra voice halves
 * the headroom available per sample: two loud talkers regularly push the sum
 * past full scale. Clamping each sample there turns the waveform into a flat
 * top (broadband harmonics — the crackle of a crowded channel), and a soft
 * knee applied to already-clamped samples cannot undo it.
 *
 * So the mix is accumulated with integer headroom and normalised once per
 * frame by a peak limiter ([PeakLimiter]):
 *
 * - a single speaker below full scale passes through **bit-exactly** (the
 *   limiter gain stays 1.0);
 * - a frame that would exceed full scale is scaled as a whole, so its peak
 *   lands on full scale and the waveform keeps its shape — no harmonic
 *   distortion, just a lower level for that frame;
 * - onset is instant (the scaling happens before anything is written, so no
 *   sample can saturate) while the release is smoothed (~0.5 s), otherwise a
 *   sustained two-speaker passage would pump at the frame rate.
 *
 * Everything here is allocation-free and runs per 20 ms frame.
 */
internal object PcmMixer {

    /** Full-scale amplitude of signed 16-bit PCM, as a float. */
    const val FULL_SCALE = 32767f

    /**
     * Add one decoded frame into the accumulator with a per-user linear
     * [gain]. Unity gain is an exact integer add — the common case.
     */
    fun accumulate(acc: IntArray, frame: ShortArray, gain: Float) {
        if (gain == 1f) {
            for (i in acc.indices) acc[i] += frame[i].toInt()
        } else {
            for (i in acc.indices) acc[i] += (frame[i] * gain).toInt()
        }
    }

    /**
     * Largest absolute sample of [acc] for the limiter input, including the
     * output volume ([outputGain]) — the limiter works on the level that will
     * actually be written, not on the pre-volume mix.
     */
    fun peak(acc: IntArray, outputGain: Float): Float {
        var peak = 0
        for (v in acc) {
            val magnitude = if (v < 0) -v else v
            if (magnitude > peak) peak = magnitude
        }
        return peak * outputGain
    }

    /** Write [acc] scaled by [gain] into [out], saturating at the 16-bit rails. */
    fun render(acc: IntArray, out: ShortArray, gain: Float) {
        for (i in out.indices) {
            val v = acc[i] * gain
            out[i] = when {
                v >= FULL_SCALE -> Short.MAX_VALUE
                v <= Short.MIN_VALUE.toFloat() -> Short.MIN_VALUE
                else -> {
                    // Round to nearest instead of truncating, so a frame
                    // scaled to land on full scale really lands on it
                    val rounded = if (v >= 0f) v + 0.5f else v - 0.5f
                    rounded.toInt().toShort()
                }
            }
        }
    }
}

/**
 * Frame-level peak limiter: instant attack, smoothed release.
 *
 * Not thread-safe; owned by the playback loop.
 */
internal class PeakLimiter(
    private val releaseCoefficient: Float = DEFAULT_RELEASE_COEFFICIENT,
) {
    /** Gain applied to the current frame (1.0 = transparent). */
    var gain: Float = 1f
        private set

    /**
     * Feed this frame's post-volume peak, get the gain to render it with.
     * Reducing is immediate; recovering is one [releaseCoefficient] step.
     */
    fun next(peak: Float): Float {
        val needed = if (peak > PcmMixer.FULL_SCALE) PcmMixer.FULL_SCALE / peak else 1f
        gain = if (needed <= gain) {
            needed
        } else {
            (gain + (1f - gain) * releaseCoefficient).coerceAtMost(1f)
        }
        return gain
    }

    fun reset() {
        gain = 1f
    }

    companion object {
        /**
         * Release step per 20 ms frame: 0.08 leaves ~90 % of the reduction
         * recovered after ~0.5 s — inaudible as pumping, fast enough that a
         * brief overlap does not leave the channel quiet.
         */
        const val DEFAULT_RELEASE_COEFFICIENT = 0.08f
    }
}
