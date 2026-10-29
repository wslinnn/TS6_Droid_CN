package dev.tsdroid.bridge

/**
 * Spacing for server-flood-limited state commands. The TS3 server drops
 * EVERYTHING from a client for tens of seconds once its command quota
 * trips (ClientIsFlooding) — observed on real devices from rapid status/
 * mute toggling — so the client must never lean on the quota: bursty
 * state commands are spaced apart before they leave.
 */
internal object CommandRateLimiter {
    const val MIN_INTERVAL_MS = 400L

    /** How long to wait before a send at [now], given the previous send time. */
    fun delayMs(now: Long, lastSentAt: Long): Long {
        val earliest = lastSentAt + MIN_INTERVAL_MS
        return if (now < earliest) earliest - now else 0L
    }
}
