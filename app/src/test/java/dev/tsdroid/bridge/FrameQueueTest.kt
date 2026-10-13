package dev.tsdroid.bridge

import dev.tsdroid.bridge.FrameQueue.Companion.GAP_RESET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FrameQueueTest {

    @Test
    fun `under capacity frames queue in FIFO order`() {
        val q = FrameQueue(4)
        val f1 = ByteArray(1)
        val f2 = ByteArray(1)
        val f3 = ByteArray(1)
        q.offer(f1, 100)
        q.offer(f2, 101)
        q.offer(f3, 102)
        assertEquals(3, q.size)
        val e1 = q.pollFirst()!!
        val e2 = q.pollFirst()!!
        val e3 = q.pollFirst()!!
        assertSame(f1, e1.frame)
        assertSame(f2, e2.frame)
        assertSame(f3, e3.frame)
        // first frame has no continuity to infer loss from
        assertEquals(GAP_RESET, e1.gap)
        assertEquals(0, e2.gap)
        assertEquals(0, e3.gap)
        assertNull(q.pollFirst())
    }

    @Test
    fun `full queue evicts oldest and keeps newest`() {
        val q = FrameQueue(10)
        val frames = Array(11) { ByteArray(1) }
        frames.forEachIndexed { index, frame -> q.offer(frame, index) }
        assertEquals(10, q.size)
        // frame 0 was evicted — the survivors start at frame 1
        assertSame(frames[1], q.pollFirst()!!.frame)
        // and the newest frame is still at the tail
        repeat(8) { q.pollFirst() }
        assertSame(frames[10], q.pollFirst()!!.frame)
        assertNull(q.pollFirst())
    }

    @Test
    fun `first frame reports reset gap`() {
        val q = FrameQueue(4)
        q.offer(ByteArray(1), 5000)
        assertEquals(GAP_RESET, q.pollFirst()!!.gap)
    }

    @Test
    fun `missing frames are reported as gap`() {
        val q = FrameQueue(8)
        q.offer(ByteArray(1), 10)
        // next seq is 14 → frames 11,12,13 went missing
        q.offer(ByteArray(1), 14)
        q.pollFirst()
        assertEquals(3, q.pollFirst()!!.gap)
    }

    @Test
    fun `u16 sequence wrap-around computes gap correctly`() {
        val q = FrameQueue(8)
        q.offer(ByteArray(1), 65534)
        // 65534 → 0 skips 65535 → one missing frame
        q.offer(ByteArray(1), 0)
        q.pollFirst()
        assertEquals(1, q.pollFirst()!!.gap)
    }

    @Test
    fun `wide jump is treated as a discontinuity not loss`() {
        val q = FrameQueue(8)
        q.offer(ByteArray(1), 100)
        // 100 → 200: beyond MAX_CONCEAL_GAP — new talkspurt / reconnect
        q.offer(ByteArray(1), 200)
        q.pollFirst()
        assertEquals(GAP_RESET, q.pollFirst()!!.gap)
    }

    @Test
    fun `duplicate sequence plays with zero gap`() {
        val q = FrameQueue(8)
        q.offer(ByteArray(1), 42)
        q.offer(ByteArray(1), 42)
        q.pollFirst()
        assertEquals(0, q.pollFirst()!!.gap)
    }

    @Test
    fun `clear empties the queue and resets sequence tracking`() {
        val q = FrameQueue(2)
        q.offer(ByteArray(1), 7)
        q.offer(ByteArray(1), 8)
        q.clear()
        assertEquals(0, q.size)
        assertNull(q.pollFirst())
        // sequence continuity is gone after a purge — no gap across it
        q.offer(ByteArray(1), 9)
        assertEquals(GAP_RESET, q.pollFirst()!!.gap)
    }
}
