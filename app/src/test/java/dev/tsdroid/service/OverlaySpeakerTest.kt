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

    // --- the pending/display window and the hand-over fallback ---
    //
    // Display is debounced by 500 ms, so during that window the caller must
    // pass the *pending* speaker rather than the applied one. These cases pin
    // that contract; the previous implementation judged against the applied
    // speaker only and got all three of them wrong.

    @Test
    fun `a short sentence that already ended must not be shown`() {
        // {7} at t=0 is pending; {7} is gone at t=350 — before the 500 ms apply.
        // With the pending speaker handed to the reducer, the stop matches and
        // the pending display is cancelled instead of applying a stopped user.
        assertEquals(
            OverlaySpeakerAction.Clear,
            overlaySpeakerAction(setOf(7), emptySet(), shownSpeakerId = 7),
        )
    }

    @Test
    fun `a handover survivor is not cleared by the previous speaker stopping`() {
        // 7 timed out while 9 had already started: shown is 9 (pending), and
        // the set still contains 9. Nothing to do — clearing here used to blank
        // the overlay while 9 was still talking.
        assertEquals(
            OverlaySpeakerAction.None,
            overlaySpeakerAction(setOf(7, 9), setOf(9), shownSpeakerId = 9),
        )
    }

    @Test
    fun `when the shown speaker stops, fall back to whoever is still talking`() {
        // 9 interjected and stopped; 7 never stopped. Blanking would hide an
        // audible speaker, so the overlay hands back to 7.
        assertEquals(
            OverlaySpeakerAction.Show(7),
            overlaySpeakerAction(setOf(7, 9), setOf(7), shownSpeakerId = 9),
        )
    }

    @Test
    fun `falling back only happens for the shown speaker`() {
        // A stop that does not involve the shown speaker must not reshuffle.
        assertEquals(
            OverlaySpeakerAction.None,
            overlaySpeakerAction(setOf(7, 9), setOf(7), shownSpeakerId = 7),
        )
    }

    @Test
    fun `the shown speaker stopping with nobody left clears`() {
        assertEquals(
            OverlaySpeakerAction.Clear,
            overlaySpeakerAction(setOf(9), emptySet(), shownSpeakerId = 9),
        )
    }

    @Test
    fun `a newcomer beats a stop even when the shown speaker left`() {
        // Both happen in one update: the newcomer takes over rather than the
        // overlay blinking through empty.
        assertEquals(
            OverlaySpeakerAction.Show(9),
            overlaySpeakerAction(setOf(7), setOf(9), shownSpeakerId = 7),
        )
    }
}
