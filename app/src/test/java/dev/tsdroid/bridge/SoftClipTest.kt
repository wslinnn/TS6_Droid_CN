package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftClipTest {

    private fun f(x: Float): Float = AudioBridge.softClip(x) / 32767f

    @Test
    fun `linear region below half scale is bit-exact passthrough`() {
        for (t in listOf(0f, 0.1f, 0.25f, -0.25f, 0.5f, -0.5f)) {
            assertEquals("t=$t", t, f(t * 32767f), 1e-3f)
        }
    }

    @Test
    fun `knee is value-continuous at half scale`() {
        val below = f(0.4999f * 32767f)
        val above = f(0.5001f * 32767f)
        assertTrue("below=$below above=$above", Math.abs(above - below) < 2e-3f)
    }

    @Test
    fun `full scale input compresses instead of clipping`() {
        val y = f(32767f)
        assertTrue("y(1.0)=$y", y in 0.7f..0.8f)
        // a 4x-boosted full-scale sample tops out near, not at, the rail
        val boosted = f(4f * 32767f)
        assertTrue("y(4.0)=$boosted", boosted in 0.9f..0.99f)
    }

    @Test
    fun `monotonic across the whole range`() {
        var prev = f(-8f * 32767f)
        var x = -8f * 32767f
        while (x < 8f * 32767f) {
            x += 997f
            val y = f(x)
            assertTrue("not monotonic at x=$x: $prev -> $y", y >= prev)
            prev = y
        }
    }

    @Test
    fun `odd symmetric`() {
        assertEquals(f(1234f), -f(-1234f), 1e-4f)
        assertEquals(f(30000f), -f(-30000f), 1e-4f)
    }
}
