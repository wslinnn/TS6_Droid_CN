package dev.tsdroid.bridge

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferWaitersTest {

    private class Refused : Exception("refused")

    @Test
    fun `every waiter of one key receives the result`() = runBlocking {
        val waiters = TransferWaiters<ByteArray>()
        val first = waiters.register(1L, "/a.png")
        val second = waiters.register(1L, "/a.png")

        assertTrue(waiters.complete(1L, "/a.png", byteArrayOf(7)))

        assertEquals(7, first.await().first().toInt())
        assertEquals(7, second.await().first().toInt())
        // The key is gone once resolved: a late duplicate event is a no-op
        assertFalse(waiters.complete(1L, "/a.png", byteArrayOf(9)))
    }

    @Test
    fun `a timed out caller does not cancel its sibling`() = runBlocking {
        val waiters = TransferWaiters<ByteArray>()
        val gone = waiters.register(1L, "/a.png")
        val alive = waiters.register(1L, "/a.png")

        // Simulates the timeout path in TsClient.downloadFile
        waiters.remove(1L, "/a.png", gone)
        assertTrue(waiters.complete(1L, "/a.png", byteArrayOf(3)))
        assertFalse(gone.isCompleted)
        assertEquals(3, alive.await().first().toInt())
    }

    @Test
    fun `same path on two channels stays isolated`() = runBlocking {
        val waiters = TransferWaiters<ByteArray>()
        val ch1 = waiters.register(1L, "/x.bin")
        val ch2 = waiters.register(2L, "/x.bin")

        assertTrue(waiters.complete(1L, "/x.bin", byteArrayOf(1)))

        assertEquals(1, ch1.await().first().toInt())
        assertFalse(ch2.isCompleted)

        assertTrue(waiters.complete(2L, "/x.bin", byteArrayOf(2)))
        assertEquals(2, ch2.await().first().toInt())
    }

    @Test
    fun `failure event without channel id fails every channel on the path`() = runBlocking {
        val waiters = TransferWaiters<ByteArray>()
        val ch1 = waiters.register(1L, "/x.bin")
        val ch2 = waiters.register(2L, "/x.bin")
        val other = waiters.register(3L, "/other.bin")

        assertTrue(waiters.failAll("/x.bin", Refused()))
        assertTrue(runCatching { ch1.await() }.isFailure)
        assertTrue(runCatching { ch2.await() }.isFailure)
        assertFalse(other.isCompleted)
        assertFalse(waiters.failAll("/x.bin", Refused()))
    }

    @Test
    fun `reset fails everything pending`() = runBlocking {
        val waiters = TransferWaiters<Boolean>()
        val a = waiters.register(1L, "/a")
        val b = waiters.register(2L, "/b")

        waiters.reset(Refused())

        assertTrue(runCatching { a.await() }.isFailure)
        assertTrue(runCatching { b.await() }.isFailure)
        assertFalse(waiters.complete(1L, "/a", true))
    }

    @Test
    fun `registration is safe from several threads`() = runBlocking {
        val waiters = TransferWaiters<ByteArray>()
        val jobs = (0 until 32).map { i ->
            async(start = CoroutineStart.DEFAULT) { waiters.register(1L, "/same") }
        }
        val all = jobs.map { it.await() }
        assertEquals(32, all.size)
        assertTrue(waiters.complete(1L, "/same", byteArrayOf(1)))
        assertTrue(all.all { it.isCompleted })
    }
}
