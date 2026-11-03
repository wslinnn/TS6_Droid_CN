package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoProbeTest {

    private fun frame(level: Short, size: Int = 960): ShortArray = ShortArray(size) { level }

    @Test
    fun `working aec shows high quiet erle`() {
        val probe = EchoProbe()
        // Residual far below the far reference — cancellation actually working
        repeat(50) {
            probe.onFarFrame(frame(10000))
            probe.onMicFrame(frame(50), farActive = true)
        }
        val stats = probe.snapshotAndReset()
        assertNotNull(stats.erleQuietDb)
        assertTrue("quiet=${stats.erleQuietDb}", stats.erleQuietDb!! > 30.0) // 20*log10(10000/50) ≈ 46dB
        assertTrue("all=${stats.erleAllDb}", stats.erleAllDb!! > 30.0)
        assertEquals(50 * EchoProbe.FAR_FRAME_MS, stats.farMs)
        assertEquals(50, stats.pairedFrames)
        assertEquals(50, stats.quietFrames)
    }

    @Test
    fun `broken aec shows low all erle and no quiet reading`() {
        val probe = EchoProbe()
        // Speaker plays loudly straight into the mic (no cancellation) — echo
        // is loud, so frames land in the loud bucket, not the quiet one
        repeat(50) {
            probe.onFarFrame(frame(10000))
            probe.onMicFrame(frame(8000), farActive = true)
        }
        val stats = probe.snapshotAndReset()
        assertNull("quiet=${stats.erleQuietDb}", stats.erleQuietDb)
        assertNotNull(stats.erleAllDb)
        assertTrue("all=${stats.erleAllDb}", stats.erleAllDb!! < 5.0) // 10*log10(10000²/8000²) ≈ 1.9dB
        assertEquals(50, stats.pairedFrames)
        assertEquals(0, stats.quietFrames)
    }

    @Test
    fun `double talk stays out of the quiet bucket`() {
        val probe = EchoProbe()
        repeat(30) {
            probe.onFarFrame(frame(10000))
            probe.onMicFrame(frame(5000), farActive = true) // loud local voice
        }
        val stats = probe.snapshotAndReset()
        assertNull(stats.erleQuietDb)
        assertEquals(30, stats.pairedFrames)
        assertEquals(0, stats.quietFrames)
    }

    @Test
    fun `mixed window yields both readings when each bucket is big enough`() {
        val probe = EchoProbe()
        repeat(30) { // loud: voice or echo passthrough
            probe.onFarFrame(frame(10000))
            probe.onMicFrame(frame(4000), farActive = true)
        }
        repeat(30) { // quiet: residual
            probe.onFarFrame(frame(10000))
            probe.onMicFrame(frame(50), farActive = true)
        }
        val stats = probe.snapshotAndReset()
        assertNotNull(stats.erleAllDb)
        assertNotNull(stats.erleQuietDb)
        assertTrue(stats.erleQuietDb!! > stats.erleAllDb!!)
        assertEquals(60, stats.pairedFrames)
        assertEquals(30, stats.quietFrames)
    }

    @Test
    fun `too few pairs yields null readings`() {
        val probe = EchoProbe()
        repeat(EchoProbe.MIN_PAIRED_FRAMES - 1) {
            probe.onFarFrame(frame(10000))
            probe.onMicFrame(frame(50), farActive = true)
        }
        val stats = probe.snapshotAndReset()
        assertNull(stats.erleAllDb)
        assertNull(stats.erleQuietDb)
        assertEquals(EchoProbe.MIN_PAIRED_FRAMES - 1, stats.pairedFrames)
    }

    @Test
    fun `mic frames while far inactive are ignored entirely`() {
        val probe = EchoProbe()
        probe.onMicFrame(frame(10000), farActive = false)
        val stats = probe.snapshotAndReset()
        assertEquals(0, stats.farMs)
        assertEquals(0, stats.pairedFrames)
        assertNull(stats.erleAllDb)
    }

    @Test
    fun `snapshot resets the window`() {
        val probe = EchoProbe()
        repeat(30) {
            probe.onFarFrame(frame(10000))
            probe.onMicFrame(frame(50), farActive = true)
        }
        probe.snapshotAndReset()
        val stats = probe.snapshotAndReset()
        assertEquals(0, stats.farMs)
        assertEquals(0, stats.pairedFrames)
        assertNull(stats.erleAllDb)
        assertNull(stats.erleQuietDb)
    }
}
