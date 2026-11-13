package dev.tsdroid.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackHealthTest {

    private fun health() = TrackHealth(streakLimit = 5, minRebuildIntervalMs = 2_000L)

    @Test
    fun `a playing healthy track never needs a rebuild`() {
        val h = health()
        var now = 0L
        repeat(100) {
            now += 20
            assertFalse(h.onPlayState(playing = true, nowMs = now))
            assertFalse(h.onWrite(written = 1920, expected = 1920, nowMs = now))
        }
    }

    @Test
    fun `a dead play state is rebuilt even while writes keep succeeding`() {
        val h = health()
        var now = 0L
        var rebuilds = 0
        repeat(20) {
            now += 20
            // The regression this class exists for: the track stopped
            // playing, but every write still "succeeds"
            val due = h.onPlayState(playing = false, nowMs = now)
            h.onWrite(written = 1920, expected = 1920, nowMs = now)
            if (due) {
                rebuilds++
                h.onRebuildAttempt(now)
            }
        }
        assertTrue("rebuilds=$rebuilds", rebuilds >= 1)
    }

    @Test
    fun `play state stall fires on the fifth consecutive frame`() {
        val h = health()
        assertFalse(h.onPlayState(playing = false, nowMs = 20))
        assertFalse(h.onPlayState(playing = false, nowMs = 40))
        assertFalse(h.onPlayState(playing = false, nowMs = 60))
        assertFalse(h.onPlayState(playing = false, nowMs = 80))
        assertTrue(h.onPlayState(playing = false, nowMs = 100))
    }

    @Test
    fun `an interleaved playing frame restarts the play state streak`() {
        val h = health()
        assertFalse(h.onPlayState(playing = false, nowMs = 20))
        assertFalse(h.onPlayState(playing = false, nowMs = 40))
        assertFalse(h.onPlayState(playing = true, nowMs = 60))
        // Counter restarted: four more stalls are still below the limit
        assertFalse(h.onPlayState(playing = false, nowMs = 80))
        assertFalse(h.onPlayState(playing = false, nowMs = 100))
        assertFalse(h.onPlayState(playing = false, nowMs = 120))
        assertFalse(h.onPlayState(playing = false, nowMs = 140))
        assertTrue(h.onPlayState(playing = false, nowMs = 160))
    }

    @Test
    fun `write stall fires on the fifth consecutive short write`() {
        val h = health()
        assertFalse(h.onWrite(written = -1, expected = 1920, nowMs = 20))
        assertFalse(h.onWrite(written = 0, expected = 1920, nowMs = 40))
        assertFalse(h.onWrite(written = 960, expected = 1920, nowMs = 60))
        assertFalse(h.onWrite(written = -6, expected = 1920, nowMs = 80))
        assertTrue(h.onWrite(written = -6, expected = 1920, nowMs = 100))
    }

    @Test
    fun `a successful write restarts only the write streak`() {
        val h = health()
        // Two play-state stalls…
        assertFalse(h.onPlayState(playing = false, nowMs = 20))
        assertFalse(h.onPlayState(playing = false, nowMs = 40))
        // …then a good write: it must not clear them
        h.onWrite(written = 1920, expected = 1920, nowMs = 60)
        assertFalse(h.onPlayState(playing = false, nowMs = 80))
        assertFalse(h.onPlayState(playing = false, nowMs = 100))
        assertTrue(h.onPlayState(playing = false, nowMs = 120))
    }

    @Test
    fun `rebuilds are rate limited`() {
        val h = health()
        // Reach the limit and rebuild at t=100
        repeat(4) { h.onPlayState(playing = false, nowMs = (it + 1) * 20L) }
        assertTrue(h.onPlayState(playing = false, nowMs = 100))
        h.onRebuildAttempt(100)

        // Still stalled, but the interval has not elapsed: no rebuild storm
        repeat(50) { i ->
            assertFalse("i=$i", h.onPlayState(playing = false, nowMs = 100L + (i + 1) * 20L))
        }
        // The new track is healthy again
        assertFalse(h.onPlayState(playing = true, nowMs = 1_200))
    }

    @Test
    fun `a rebuild attempt clears both streaks`() {
        val h = health()
        repeat(4) { h.onWrite(written = -1, expected = 1920, nowMs = 20L * (it + 1)) }
        repeat(4) { h.onPlayState(playing = false, nowMs = 20L * (it + 1)) }
        h.onRebuildAttempt(100)
        // Four more of each is still below the limit
        repeat(4) { i ->
            assertFalse(h.onWrite(written = -1, expected = 1920, nowMs = 2_100L + i * 20))
            assertFalse(h.onPlayState(playing = false, nowMs = 2_100L + i * 20))
        }
        assertTrue(h.onWrite(written = -1, expected = 1920, nowMs = 2_200))
    }

    @Test
    fun `externally requested rebuilds are rate limited too`() {
        val h = health()
        // Nothing attempted yet: a route change may rebuild immediately
        assertTrue(h.canAttemptRebuild(0))
        h.onRebuildAttempt(1_000)
        assertFalse(h.canAttemptRebuild(1_500))
        assertFalse(h.canAttemptRebuild(2_999))
        assertTrue(h.canAttemptRebuild(3_000))
    }

    @Test
    fun `streaks are exposed for diagnostics`() {
        val h = health()
        h.onWrite(written = 0, expected = 1920, nowMs = 20)
        h.onPlayState(playing = false, nowMs = 20)
        org.junit.Assert.assertEquals(1, h.writeStallStreak)
        org.junit.Assert.assertEquals(1, h.playStateStallStreak)
    }
}
