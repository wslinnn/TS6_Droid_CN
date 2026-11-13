package dev.tsdroid.bridge

/**
 * Decides when the output [android.media.AudioTrack] has to be replaced.
 *
 * Two *independent* stall reasons get here:
 *
 * - the track refuses audio — `write` returns short or negative;
 * - the track is no longer playing while we keep feeding it.
 *
 * They must not share a counter. A successful write proves nothing about
 * playback (a track whose route was removed happily accepts writes), and
 * sharing one counter let the write-success path clear the play-state streak
 * on every frame — which made the play-state branch unreachable in exactly
 * the situation it exists for: audio flowing into a dead track.
 *
 * The rebuild itself is rate limited: a device that keeps refusing a new
 * track must not be re-created on every frame.
 *
 * Not thread-safe; owned by the playback loop.
 */
internal class TrackHealth(
    private val streakLimit: Int = DEFAULT_STREAK_LIMIT,
    private val minRebuildIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    private var writeStall = 0
    private var playStateStall = 0
    private var lastRebuildAt = NO_REBUILD

    /**
     * Note the track's current play state. Returns true when the accumulated
     * stall calls for a rebuild now.
     */
    fun onPlayState(playing: Boolean, nowMs: Long): Boolean {
        if (playing) {
            playStateStall = 0
            return false
        }
        playStateStall++
        return due(playStateStall, nowMs)
    }

    /**
     * Note the outcome of one frame write ([written] bytes accepted of
     * [expected]). Returns true when the accumulated stall calls for a rebuild
     * now.
     */
    fun onWrite(written: Int, expected: Int, nowMs: Long): Boolean {
        if (written == expected) {
            writeStall = 0
            return false
        }
        writeStall++
        return due(writeStall, nowMs)
    }

    /**
     * Record a rebuild attempt (whether or not a new track could be created —
     * a failed attempt must also be rate limited).
     */
    fun onRebuildAttempt(nowMs: Long) {
        lastRebuildAt = nowMs
        writeStall = 0
        playStateStall = 0
    }

    /** Consecutive stalled writes so far (diagnostics). */
    val writeStallStreak: Int get() = writeStall

    /** Consecutive non-playing reads so far (diagnostics). */
    val playStateStallStreak: Int get() = playStateStall

    private fun due(streak: Int, nowMs: Long): Boolean {
        if (streak < streakLimit) return false
        // No rebuild yet: nothing to rate limit against. (Starting at 0 would
        // silently disable the first 2 s after boot, when elapsedRealtime
        // itself is still small.)
        return lastRebuildAt == NO_REBUILD || nowMs - lastRebuildAt >= minRebuildIntervalMs
    }

    companion object {
        /** 5 frames = 100 ms at 20 ms per frame. */
        const val DEFAULT_STREAK_LIMIT = 5

        /** Minimum spacing between automatic rebuilds. */
        const val DEFAULT_MIN_INTERVAL_MS = 2_000L

        private const val NO_REBUILD = Long.MIN_VALUE
    }
}
