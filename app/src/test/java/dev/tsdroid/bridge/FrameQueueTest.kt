package dev.tsdroid.bridge

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
        q.offer(f1)
        q.offer(f2)
        q.offer(f3)
        assertEquals(3, q.size)
        assertSame(f1, q.pollFirst())
        assertSame(f2, q.pollFirst())
        assertSame(f3, q.pollFirst())
        assertNull(q.pollFirst())
    }

    @Test
    fun `full queue evicts oldest and keeps newest`() {
        val q = FrameQueue(10)
        val frames = Array(11) { ByteArray(1) }
        frames.forEach { q.offer(it) }
        assertEquals(10, q.size)
        // frame 0 was evicted — the survivors start at frame 1
        assertSame(frames[1], q.pollFirst())
        // and the newest frame is still at the tail
        repeat(8) { q.pollFirst() }
        assertSame(frames[10], q.pollFirst())
        assertNull(q.pollFirst())
    }

    @Test
    fun `clear empties the queue`() {
        val q = FrameQueue(2)
        q.offer(ByteArray(1))
        q.offer(ByteArray(1))
        q.clear()
        assertEquals(0, q.size)
        assertNull(q.pollFirst())
    }
}
