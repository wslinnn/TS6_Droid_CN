package dev.tsdroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decides what the floating overlay should show when the speaking set changes.
 *
 * The overlay can only show one person, so "who is speaking" (a set, from the
 * talk-status StateFlows) has to be reduced to a single active speaker. It used
 * to read the event stream directly, which meant a dropped
 * `talk_status_stop` left the overlay showing someone who had already stopped.
 */
class OverlaySpeakerTest {

    @Test
    fun `a new speaker takes the overlay`() {
        assertEquals(
            OverlaySpeakerAction.Show(7),
            overlaySpeakerAction(emptySet(), setOf(7), shownSpeakerId = null),
        )
    }

    @Test
    fun `a second speaker takes over from the first`() {
        assertEquals(
            "the newest speaker wins",
            OverlaySpeakerAction.Show(9),
            overlaySpeakerAction(setOf(7), setOf(7, 9), shownSpeakerId = 7),
        )
    }

    @Test
    fun `the shown speaker stopping clears the overlay`() {
        assertEquals(
            OverlaySpeakerAction.Clear,
            overlaySpeakerAction(setOf(7), emptySet(), shownSpeakerId = 7),
        )
    }

    @Test
    fun `a speaker nobody is showing stopping does not clear the overlay`() {
        assertEquals(
            OverlaySpeakerAction.None,
            overlaySpeakerAction(setOf(7, 9), setOf(9), shownSpeakerId = 9),
        )
    }

    @Test
    fun `a missed update still converges instead of leaving the ring lit`() {
        // The event-stream version depended on the stop event arriving at all.
        // Set diffs converge: whatever was missed, the next observed set simply
        // no longer contains the speaker.
        var shown: Int? = 7
        val action = overlaySpeakerAction(setOf(7), emptySet(), shownSpeakerId = shown)
        if (action is OverlaySpeakerAction.Clear) shown = null
        assertNull("the overlay must not still show a stopped speaker", shown)
    }

    @Test
    fun `starting and stopping in the same update shows the new speaker`() {
        // 7 stopped and 9 started between two reads: the newcomer wins over
        // clearing, so the overlay does not blink through empty.
        assertEquals(
            OverlaySpeakerAction.Show(9),
            overlaySpeakerAction(setOf(7), setOf(9), shownSpeakerId = 7),
        )
    }

    @Test
    fun `an unchanged set does nothing`() {
        assertEquals(
            OverlaySpeakerAction.None,
            overlaySpeakerAction(setOf(7, 9), setOf(7, 9), shownSpeakerId = 7),
        )
    }

    @Test
    fun `a purge to empty clears whoever was shown`() {
        assertEquals(
            OverlaySpeakerAction.Clear,
            overlaySpeakerAction(setOf(7, 9), emptySet(), shownSpeakerId = 9),
        )
        assertEquals(
            OverlaySpeakerAction.Clear,
            overlaySpeakerAction(setOf(7, 9), emptySet(), shownSpeakerId = 7),
        )
    }

    @Test
    fun `nothing shown and nothing speaking does nothing`() {
        assertEquals(
            OverlaySpeakerAction.None,
            overlaySpeakerAction(emptySet(), emptySet(), shownSpeakerId = null),
        )
    }
}
