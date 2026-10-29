package dev.tsdroid.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {

    @Test
    fun `doubles from base then caps at max`() {
        assertEquals(3_000L, ReconnectBackoff.delayBeforeAttempt(1))
        assertEquals(6_000L, ReconnectBackoff.delayBeforeAttempt(2))
        assertEquals(12_000L, ReconnectBackoff.delayBeforeAttempt(3))
        assertEquals(24_000L, ReconnectBackoff.delayBeforeAttempt(4))
        assertEquals(48_000L, ReconnectBackoff.delayBeforeAttempt(5))
        assertEquals(60_000L, ReconnectBackoff.delayBeforeAttempt(6))
        assertEquals(60_000L, ReconnectBackoff.delayBeforeAttempt(7))
        assertEquals(60_000L, ReconnectBackoff.delayBeforeAttempt(8))
    }

    @Test
    fun `out-of-range attempts clamp into range`() {
        assertEquals(ReconnectBackoff.delayBeforeAttempt(1), ReconnectBackoff.delayBeforeAttempt(0))
        assertEquals(ReconnectBackoff.delayBeforeAttempt(-5), ReconnectBackoff.delayBeforeAttempt(1))
        assertEquals(
            ReconnectBackoff.delayBeforeAttempt(ReconnectBackoff.MAX_ATTEMPTS),
            ReconnectBackoff.delayBeforeAttempt(999),
        )
    }

    @Test
    fun `total window outlasts a typical server restart`() {
        var total = 0L
        for (attempt in 1..ReconnectBackoff.MAX_ATTEMPTS) {
            total += ReconnectBackoff.delayBeforeAttempt(attempt)
        }
        assert(total >= 240_000L) { "retry window $total ms must cover a ~60s server restart with slack" }
    }
}
