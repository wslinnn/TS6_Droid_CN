package dev.tsdroid.service

/**
 * Reconnect retry schedule: exponential with a hard cap.
 *
 * A dropped session is most often a server restart (30–60s downtime) or a
 * dead network path; the window must outlast the former, and the cap bounds
 * each wait so a permanently dead address still fails over quickly.
 */
object ReconnectBackoff {
    const val BASE_DELAY_MS = 3_000L
    const val MAX_DELAY_MS = 60_000L
    const val MAX_ATTEMPTS = 8

    /**
     * Delay before the given 1-based attempt: 3s, 6s, 12s, 24s, 48s, then
     * 60s for every further attempt. Out-of-range attempts clamp into range.
     */
    fun delayBeforeAttempt(attempt: Int): Long {
        val clamped = attempt.coerceIn(1, MAX_ATTEMPTS)
        var delay = BASE_DELAY_MS
        repeat(clamped - 1) {
            delay = (delay * 2).coerceAtMost(MAX_DELAY_MS)
        }
        return delay
    }
}
