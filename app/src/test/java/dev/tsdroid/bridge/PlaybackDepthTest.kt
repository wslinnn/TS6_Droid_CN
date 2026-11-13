package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDepthTest {

    private fun depth() = PlaybackDepth(sampleRate = 48000)

    @Test
    fun `queued depth is written minus played`() {
        val d = depth()
        d.onFramesWritten(4800) // 100 ms handed to the track
        assertEquals(100L, d.bufferedMs(0))
        assertEquals(50L, d.bufferedMs(2400))
        assertEquals(0L, d.bufferedMs(4800))
    }

    @Test
    fun `a signed negative head is read unsigned, not sign extended`() {
        val d = depth()
        d.onFramesWritten(4800)
        d.bufferedMs(0)
        // int -1 == u32 0xFFFFFFFF: past 2^31 (12.4 h) the counter reads
        // negative and must mean "far ahead", not a similarly sized negative
        assertEquals(0L, d.bufferedMs(-1))
        assertTrue("wraps=${d.wraps}", d.wraps == 0)
    }

    @Test
    fun `a head wrap keeps the timeline instead of inflating the depth`() {
        val d = depth()
        // Written counter just past 2^32, head still 960 frames behind it
        d.onFramesWritten(Int.MAX_VALUE)
        d.onFramesWritten(Int.MAX_VALUE)
        d.onFramesWritten(2) // written = 2^32
        assertEquals(20L, d.bufferedMs(-960)) // head u32 = 2^32 - 960

        // The counter wraps to 0 while playback continues
        assertEquals(0L, d.bufferedMs(0))
        assertEquals(1, d.wraps)

        // …and the depth is measured correctly on the far side of the wrap
        d.onFramesWritten(960)
        assertEquals(20L, d.bufferedMs(0))
        d.onFramesWritten(960)
        assertEquals(40L, d.bufferedMs(0))
    }

    @Test
    fun `a mid-range counter restart rebases instead of faking a wrap`() {
        val d = depth()
        d.onFramesWritten(9600) // 200 ms queued
        assertEquals(200L, d.bufferedMs(0))
        assertEquals(180L, d.bufferedMs(960))
        // Head jumps back to 0 without a wrap (track flushed/recreated):
        // a wrap offset here would keep the depth at zero forever
        assertEquals(0L, d.bufferedMs(0))
        assertEquals(0, d.wraps)
        d.onFramesWritten(960)
        assertEquals(20L, d.bufferedMs(0))
    }

    @Test
    fun `reset starts a fresh timeline`() {
        val d = depth()
        d.onFramesWritten(48000)
        assertEquals(1000L, d.bufferedMs(0))
        d.reset()
        assertEquals(0L, d.bufferedMs(0))
        d.onFramesWritten(960)
        assertEquals(20L, d.bufferedMs(0))
    }

    @Test
    fun `reset clears the accumulated wrap offset`() {
        val d = depth()
        d.onFramesWritten(Int.MAX_VALUE)
        d.onFramesWritten(Int.MAX_VALUE)
        d.onFramesWritten(2)
        d.bufferedMs(-960)
        d.bufferedMs(0) // forces the wrap
        assertEquals(1, d.wraps)
        d.reset()
        d.onFramesWritten(960)
        // A stale offset would read this as 2^32 frames behind instead of 20 ms
        assertEquals(20L, d.bufferedMs(0))
    }

    @Test
    fun `depth never goes negative when the read races the write`() {
        val d = depth()
        assertEquals(0L, d.bufferedMs(0))
        d.onFramesWritten(100)
        assertEquals(0L, d.bufferedMs(960))
    }
}
