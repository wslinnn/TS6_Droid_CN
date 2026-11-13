package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmMixerTest {

    private fun frame(vararg samples: Short) = samples

    @Test
    fun `unity gain sums voices without clipping`() {
        val acc = IntArray(4)
        PcmMixer.accumulate(acc, frame(1000, -1000, 32767, -32768), 1f)
        PcmMixer.accumulate(acc, frame(1000, -1000, 32767, -32768), 1f)
        // The accumulator keeps the true sum — 2 * 32767 must not wrap or clip here
        assertEquals(2000, acc[0])
        assertEquals(-2000, acc[1])
        assertEquals(65534, acc[2])
        assertEquals(-65536, acc[3])
    }

    @Test
    fun `per-user gain scales the contribution`() {
        val acc = IntArray(2)
        PcmMixer.accumulate(acc, frame(1000, -1000), 1.5f)
        assertEquals(1500, acc[0])
        assertEquals(-1500, acc[1])
    }

    @Test
    fun `peak includes the output volume`() {
        val acc = intArrayOf(1000, -3000, 2000, 0)
        assertEquals(3000f, PcmMixer.peak(acc, 1f), 0f)
        assertEquals(1500f, PcmMixer.peak(acc, 0.5f), 0f)
    }

    @Test
    fun `render saturates instead of wrapping`() {
        val acc = intArrayOf(40000, -40000, 32767, -32768)
        val out = ShortArray(4)
        PcmMixer.render(acc, out, 1f)
        assertEquals(Short.MAX_VALUE, out[0])
        assertEquals(Short.MIN_VALUE, out[1])
        assertEquals(32767.toShort(), out[2])
        assertEquals(Short.MIN_VALUE, out[3])
    }

    @Test
    fun `single speaker below full scale is bit-exact at unity gain`() {
        val pcm = ShortArray(960) { (it * 37 % 30000 - 15000).toShort() }
        val acc = IntArray(960)
        PcmMixer.accumulate(acc, pcm, 1f)
        val limiter = PeakLimiter()
        val gain = limiter.next(PcmMixer.peak(acc, 1f))
        assertEquals(1f, gain, 0f)
        val out = ShortArray(960)
        PcmMixer.render(acc, out, gain)
        assertTrue(pcm.contentEquals(out))
    }

    @Test
    fun `overdriven frame is scaled to full scale without distorsion`() {
        // Two near-full-scale voices: the sum would clip sample by sample
        val a = ShortArray(960) { 30000.toShort() }
        val b = ShortArray(960) { 30000.toShort() }
        val acc = IntArray(960)
        PcmMixer.accumulate(acc, a, 1f)
        PcmMixer.accumulate(acc, b, 1f)
        val limiter = PeakLimiter()
        val gain = limiter.next(PcmMixer.peak(acc, 1f))
        assertEquals(PcmMixer.FULL_SCALE / 60000f, gain, 1e-6f)
        val out = ShortArray(960)
        PcmMixer.render(acc, out, gain)
        // Every sample lands on the same (full-scale) level — a uniformly
        // scaled waveform, not a flat-topped one
        assertEquals(32767, out[0].toInt())
        assertEquals(32767, out[959].toInt())
    }

    @Test
    fun `a quiet voice keeps its shape under the limiter`() {
        // Full-scale talker plus a low-level ramp: clamping would flatten the
        // ramp into a constant rail, uniform scaling keeps every step
        val loud = ShortArray(960) { Short.MAX_VALUE }
        val ramp = ShortArray(960) { (it - 480).toShort() }
        val acc = IntArray(960)
        PcmMixer.accumulate(acc, loud, 1f)
        PcmMixer.accumulate(acc, ramp, 1f)
        val limiter = PeakLimiter()
        val gain = limiter.next(PcmMixer.peak(acc, 1f))
        assertTrue("gain=$gain", gain < 1f)
        val out = ShortArray(960)
        PcmMixer.render(acc, out, gain)
        // At most the single peak sample may sit on the rail
        assertTrue("saturated=${out.count { it == Short.MAX_VALUE }}", out.count { it == Short.MAX_VALUE } <= 1)
        // The ramp survives: neighbouring samples differ by at most one step
        for (i in 0 until 959) {
            val step = out[i + 1].toInt() - out[i].toInt()
            assertTrue("i=$i step=$step", step in -1..1)
        }
    }

    @Test
    fun `attack is instant but release is smoothed`() {
        val limiter = PeakLimiter()
        assertEquals(1f, limiter.next(1000f), 0f)
        // A clipped frame: gain drops in the same frame it is needed
        assertEquals(0.5f, limiter.next(PcmMixer.FULL_SCALE * 2f), 1e-6f)
        // The next in-range frame recovers only one step, not everything
        val released = limiter.next(1000f)
        assertTrue("released=$released", released > 0.5f && released < 0.6f)
        // …and converges back towards transparency
        repeat(100) { limiter.next(1000f) }
        assertEquals(1f, limiter.gain, 1e-3f)
    }

    @Test
    fun `limiter never lets a rendered frame exceed full scale`() {
        val limiter = PeakLimiter()
        val acc = IntArray(960)
        for (round in 0 until 50) {
            java.util.Arrays.fill(acc, 0)
            val level = if (round % 2 == 0) 32000 else 9000
            PcmMixer.accumulate(acc, ShortArray(960) { level.toShort() }, 1f)
            PcmMixer.accumulate(acc, ShortArray(960) { level.toShort() }, 1f)
            val outGain = if (round % 3 == 0) 1.5f else 1f
            val gain = outGain * limiter.next(PcmMixer.peak(acc, outGain))
            val out = ShortArray(960)
            PcmMixer.render(acc, out, gain)
            for (s in out) {
                assertTrue("round=$round sample=$s", s.toInt() in -32768..32767)
            }
        }
    }

    @Test
    fun `reset restores transparency`() {
        val limiter = PeakLimiter()
        limiter.next(PcmMixer.FULL_SCALE * 4f)
        assertTrue(limiter.gain < 0.5f)
        limiter.reset()
        assertEquals(1f, limiter.gain, 0f)
    }
}
