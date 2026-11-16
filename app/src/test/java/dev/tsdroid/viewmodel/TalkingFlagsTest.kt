package dev.tsdroid.viewmodel

import dev.tslib.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speaking flag decision.
 *
 * The rule these tests pin down: talk state comes from the live speaking set,
 * never from the roster snapshot. A snapshot's `is_talking` is the value at
 * build time and roster snapshots are only rebuilt on roster changes, so a
 * user who was talking during a rebuild would otherwise stay flagged forever.
 */
class TalkingFlagsTest {

    private fun user(
        id: Int,
        isTalking: Boolean = false,
        nickname: String = "user$id",
    ) = User(
        id, "uid-$id", 0L, 10L, nickname, 0, isTalking,
        false, false, true, true, false, false,
        false, false, false, 0, "", longArrayOf(), 0L,
        "android", "1.0", "", "", null, 0L, null, null,
    )

    @Test
    fun `a stale snapshot true is cleared when nobody is speaking`() {
        // The regression: user 7 was talking when the snapshot was rebuilt,
        // then stopped. No fresh snapshot ever arrives.
        val roster = listOf(user(7, isTalking = true))
        val patched = patchTalkingFlags(
            users = roster,
            talkingUserIds = emptySet(),
            selfId = 1,
            isSelfTalking = false,
        )
        assertFalse("a stopped speaker must not stay lit", patched[0].isTalking)
    }

    @Test
    fun `a speaker in the live set is patched on even if the snapshot says otherwise`() {
        val roster = listOf(user(7, isTalking = false))
        val patched = patchTalkingFlags(
            users = roster,
            talkingUserIds = setOf(7),
            selfId = 1,
            isSelfTalking = false,
        )
        assertTrue(patched[0].isTalking)
    }

    @Test
    fun `unchanged rows are passed through without allocation`() {
        val notTalking = user(7, isTalking = false)
        val talking = user(8, isTalking = true)
        val patched = patchTalkingFlags(
            users = listOf(notTalking, talking),
            talkingUserIds = setOf(8),
            selfId = 1,
            isSelfTalking = false,
        )
        assertSame("an already-correct row must not be copied", notTalking, patched[0])
        assertSame(talking, patched[1])
    }

    @Test
    fun `the own row is driven by local VAD, not by the server`() {
        // The server never echoes our own talk status back, so the own row
        // must light up from local VAD alone.
        val roster = listOf(user(1, isTalking = false))
        val speaking = patchTalkingFlags(
            users = roster,
            talkingUserIds = emptySet(),
            selfId = 1,
            isSelfTalking = true,
        )
        assertTrue("own row must follow local VAD", speaking[0].isTalking)

        val quiet = patchTalkingFlags(
            users = speaking,
            talkingUserIds = emptySet(),
            selfId = 1,
            isSelfTalking = false,
        )
        assertFalse(quiet[0].isTalking)
    }

    @Test
    fun `local VAD does not light up another user's row`() {
        val roster = listOf(user(7, isTalking = false))
        val patched = patchTalkingFlags(
            users = roster,
            talkingUserIds = emptySet(),
            selfId = 1,
            // Local VAD active, but the self id is not in this roster
            isSelfTalking = true,
        )
        assertFalse(patched[0].isTalking)
    }

    @Test
    fun `a null self id before the connection is ready matches nobody`() {
        val roster = listOf(user(0, isTalking = false))
        val patched = patchTalkingFlags(
            users = roster,
            talkingUserIds = emptySet(),
            selfId = null,
            isSelfTalking = true,
        )
        assertFalse("client id 0 with no self id must not be treated as self", patched[0].isTalking)
    }

    @Test
    fun `both local VAD and the server set are honoured together`() {
        val roster = listOf(user(1), user(7), user(8))
        val patched = patchTalkingFlags(
            users = roster,
            talkingUserIds = setOf(7),
            selfId = 1,
            isSelfTalking = true,
        )
        assertEquals(listOf(1, 7), patched.filter { it.isTalking }.map { it.id })
    }

    @Test
    fun `all non-talk fields survive the copy`() {
        val roster = listOf(user(7, isTalking = false, nickname = "alice"))
        val patched = patchTalkingFlags(
            users = roster,
            talkingUserIds = setOf(7),
            selfId = 1,
            isSelfTalking = false,
        )
        val patchedUser = patched[0]
        assertEquals(7, patchedUser.id)
        assertEquals("uid-7", patchedUser.uid)
        assertEquals("alice", patchedUser.nickname)
        assertEquals(10L, patchedUser.channelId)
    }
}
