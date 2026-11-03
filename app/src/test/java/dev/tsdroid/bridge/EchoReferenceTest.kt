package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoReferenceTest {

    private fun frame(level: Short, size: Int = 960): ShortArray = ShortArray(size) { level }

    private fun ShortArray.allZero() = all { it == 0.toShort() }

    @Test
    fun `first read pairs the first pushed frame with zero alignment`() {
        val ref = EchoReference()
        val out = ShortArray(960)
        repeat(3) { i -> ref.push(frame((i + 1).toShort()), nowMs = (i + 1) * 20L) }

        assertTrue(ref.read(out))
        assertEquals(1, out[0].toInt())
        assertTrue(ref.read(out))
        assertEquals(2, out[0].toInt())
        assertTrue(ref.read(out))
        assertEquals(3, out[0].toInt())
        val stats = ref.snapshotAndReset()
        assertEquals(0, stats.padFrames)
        assertEquals(0, stats.skipFrames)
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
        assertEquals(0, stats.padFrames)
        assertEquals(0, stats.skipFrames)
    }

    @Test
    fun `stale reference skips forward bounded to the lag limit`() {
        val ref = EchoReference()
        val out = ShortArray(960)
        // 30 frames pushed (600 ms) with no reads: the oldest data is stale.
        repeat(30) { i -> ref.push(frame((i + 1).toShort()), nowMs = (i + 1) * 20L) }

        assertTrue(ref.read(out))
        // Skipped to the 300 ms lag bound: 14400 samples in = frame 16 (index 15)
        assertEquals(16, out[0].toInt())
        val stats = ref.snapshotAndReset()
        assertEquals(1, stats.skipFrames)
    }

    @Test
    fun `idle stream reads zeros and stays wall-clock aligned on resume`() {
        val ref = EchoReference()
        val out = ShortArray(960)
        ref.push(frame(7), nowMs = 20L)
        assertTrue(ref.read(out))
        assertEquals(7, out[0].toInt())

        // Playback stops: re-reading the last played frame would feed a
        // phantom reference, so idle reads must yield zeros.
        assertFalse(ref.read(out))
        assertTrue(out.allZero())
        assertFalse(ref.read(out))
        assertTrue(out.allZero())

        // Playback resumes 200 ms later. The push-side zero-fill represents
        // the silent gap, so the consumer first reads those zeros (speaker
        // was silent) and only then the resumed frame — wall-clock aligned,
        // no resync needed.
        ref.push(frame(8), nowMs = 220L)
        repeat(7) {
            ref.read(out)
            assertTrue("gap frame $it must read as zeros", out.allZero())
        }
        assertTrue(ref.read(out))
        assertEquals(8, out[0].toInt())
        val stats = ref.snapshotAndReset()
        assertEquals(0, stats.resyncs)
        assertEquals(2, stats.padFrames) // the two idle reads only
    }

    @Test
    fun `idle beyond the zero-fill cap realigns on resume`() {
        val ref = EchoReference()
        val out = ShortArray(960)
        ref.push(frame(1), nowMs = 20L)
        assertTrue(ref.read(out))
        // ~800 ms of idle: longer than the 500 ms zero-fill cap, so the
        // consumer necessarily runs past everything the stream can supply.
        repeat(40) { ref.read(out) }
        ref.push(frame(2), nowMs = 900L)

        assertTrue(ref.read(out))
        assertEquals(2, out[0].toInt()) // realigned to the resumed frame
        val stats = ref.snapshotAndReset()
        assertEquals(1, stats.resyncs)
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
