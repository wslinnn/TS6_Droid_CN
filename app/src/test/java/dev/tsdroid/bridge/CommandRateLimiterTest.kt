package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandRateLimiterTest {

    @Test
    fun `no wait when never sent or interval elapsed`() {
        assertEquals(0L, CommandRateLimiter.delayMs(now = 10_000, lastSentAt = 0))
        assertEquals(
            0L,
            CommandRateLimiter.delayMs(
                now = 10_000,
                lastSentAt = 10_000 - CommandRateLimiter.MIN_INTERVAL_MS,
            ),
        )
        assertEquals(
            0L,
            CommandRateLimiter.delayMs(
                now = 10_000,
                lastSentAt = 10_000 - CommandRateLimiter.MIN_INTERVAL_MS - 1,
            ),
        )
    }

    @Test
    fun `waits out the remainder inside the interval`() {
        val lastSent = 10_000L
        val now = lastSent + CommandRateLimiter.MIN_INTERVAL_MS - 150
        assertEquals(
            150L,
            CommandRateLimiter.delayMs(now = now, lastSentAt = lastSent),
        )
    }

    @Test
    fun `burst spacing keeps every gap at least the interval`() {
        var now = 50_000L
        var last = 0L
        repeat(20) {
            val wait = CommandRateLimiter.delayMs(now, last)
            now += wait + 1 // simulate the send taking place, 1ms later
            if (last != 0L) {
                assert(now - last >= CommandRateLimiter.MIN_INTERVAL_MS) {
                    "gap ${now - last}ms below minimum"
                }
            }
            last = now
        }
    }
}
