package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoReferenceTest {

    private fun frame(level: Short, size: Int = 960): ShortArray = ShortArray(size) { level }

    private fun ShortArray.allZero() = all { it == 0.toShort() }

    @Test
    fun `alignment pads five frames then pairs the first pushed frame`() {
        val ref = EchoReference()
        val out = ShortArray(960)
        repeat(6) { i -> ref.push(frame((i + 1).toShort()), nowMs = (i + 1) * 20L) }

        repeat(5) {
            assertFalse("alignment frame $it must pad", ref.read(out))
            assertTrue(out.allZero())
        }
        assertTrue(ref.read(out))
        assertEquals(1, out[0].toInt()) // oldest pushed frame
        assertEquals(0, ref.snapshotAndReset().skipFrames)
    }

    @Test
    fun `idle playback gap becomes zero reference frames`() {
        val ref = EchoReference()
        val out = ShortArray(960)
        ref.push(frame(1), nowMs = 20L)
        // 100 ms later the playback loop resumes: the speaker played silence
        // for 5 frames, one of which is the new frame itself. (Longer gaps
        // fall under the 300 ms stale bound and skip instead — by design.)
        ref.push(frame(2), nowMs = 120L)

        repeat(5) { ref.read(out) } // alignment
        assertTrue(ref.read(out))
        assertEquals(1, out[0].toInt()) // frame before the gap
        repeat(4) {
            // Zero-filled gap frames are real stream content (the speaker
            // emitted silence), so reads succeed with all-zero output.
            ref.read(out)
            assertTrue("gap frame $it must read as zeros", out.allZero())
        }
        assertTrue(ref.read(out))
        assertEquals(2, out[0].toInt()) // resumed frame after 4 zero frames
        val stats = ref.snapshotAndReset()
        assertEquals(5, stats.padFrames) // alignment only — gaps are filled, not missing
        assertEquals(0, stats.skipFrames)
    }

    @Test
    fun `stale reference skips forward bounded to the lag limit`() {
        val ref = EchoReference()
        val out = ShortArray(960)
        // 30 frames pushed (600 ms) with no reads: the oldest data is stale.
        repeat(30) { i -> ref.push(frame((i + 1).toShort()), nowMs = (i + 1) * 20L) }

        repeat(5) { ref.read(out) } // alignment
        assertTrue(ref.read(out))
        // Skipped to the 300 ms lag bound: 14400 samples in = frame 16 (index 15)
        assertEquals(16, out[0].toInt())
        val stats = ref.snapshotAndReset()
        assertEquals(1, stats.skipFrames)
        assertEquals(0, stats.resyncs)
    }

    @Test
    fun `capture ahead of the stream re-arms alignment`() {
        val ref = EchoReference()
        val out = ShortArray(960)
        ref.push(frame(7), nowMs = 20L)
        // Capture keeps reading with no playback: after consuming the single
        // frame the stream can never catch up, so alignment must re-arm.
        repeat(7) { ref.read(out) }
        val stats = ref.snapshotAndReset()
        assertTrue("resyncs=${stats.resyncs}", stats.resyncs >= 1)
    }

    @Test
    fun `read before any push pads zeros`() {
        val ref = EchoReference()
        val out = ShortArray(960)
        assertFalse(ref.read(out))
        assertTrue(out.allZero())
    }

    @Test
    fun `reset clears stream and counters`() {
        val ref = EchoReference()
        val out = ShortArray(960)
        repeat(6) { i -> ref.push(frame((i + 1).toShort()), nowMs = (i + 1) * 20L) }
        ref.read(out)
        ref.reset()

        assertFalse(ref.read(out))
        assertTrue(out.allZero())
        val stats = ref.snapshotAndReset()
        assertEquals(0, stats.padFrames)
        assertEquals(0, stats.skipFrames)
        assertEquals(0, stats.resyncs)
    }
}
