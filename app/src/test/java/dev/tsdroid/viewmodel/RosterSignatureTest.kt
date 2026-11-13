package dev.tsdroid.viewmodel

import dev.tslib.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The roster signature decides whether the per-emission work (avatar
 * downloads, per-user audio sync, legacy conversation merge) runs again.
 * Talk status must NOT change it — the users flow re-emits on every
 * talk start/stop — while anything the gated work depends on must.
 */
class RosterSignatureTest {

    private fun user(
        id: Int = 1,
        uid: String? = "uid-1",
        channelId: Long = 10,
        avatarId: String? = "av-1",
        isTalking: Boolean = false,
        nickname: String = "alice",
        isAway: Boolean = false,
        isRecording: Boolean = false,
        isInputMuted: Boolean = false,
        isOutputMuted: Boolean = false,
        hasInputHardware: Boolean = true,
    ) = User(
        id, uid, 0L, channelId, nickname, 0, isTalking,
        isInputMuted, isOutputMuted, hasInputHardware, true, isAway, isRecording,
        false, false, false, 0, "", longArrayOf(), 0L,
        "android", "1.0", "", "", avatarId, 0L, null, null,
    )

    @Test
    fun `talk status does not change the signature`() {
        val quiet = listOf(user(isTalking = false), user(id = 2, uid = "uid-2"))
        val talking = listOf(user(isTalking = true), user(id = 2, uid = "uid-2", isTalking = true))
        assertEquals(rosterSignatureOf(quiet), rosterSignatureOf(talking))
    }

    @Test
    fun `nickname does not change the signature`() {
        assertEquals(
            rosterSignatureOf(listOf(user(nickname = "alice"))),
            rosterSignatureOf(listOf(user(nickname = "alice (mobile)"))),
        )
    }

    @Test
    fun `a join or a leave changes the signature`() {
        val one = rosterSignatureOf(listOf(user()))
        val two = rosterSignatureOf(listOf(user(), user(id = 2, uid = "uid-2")))
        assertNotEquals(one, two)
        assertNotEquals(two, one)
    }

    @Test
    fun `avatar change changes the signature`() {
        assertNotEquals(
            rosterSignatureOf(listOf(user(avatarId = "av-1"))),
            rosterSignatureOf(listOf(user(avatarId = "av-2"))),
        )
        // Adding or removing an avatar counts too
        assertNotEquals(
            rosterSignatureOf(listOf(user(avatarId = null))),
            rosterSignatureOf(listOf(user(avatarId = "av-1"))),
        )
    }

    @Test
    fun `channel move changes the signature`() {
        assertNotEquals(
            rosterSignatureOf(listOf(user(channelId = 10))),
            rosterSignatureOf(listOf(user(channelId = 11))),
        )
    }

    @Test
    fun `client id and uid changes change the signature`() {
        assertNotEquals(
            rosterSignatureOf(listOf(user(id = 1))),
            rosterSignatureOf(listOf(user(id = 2))),
        )
        assertNotEquals(
            rosterSignatureOf(listOf(user(uid = "uid-1"))),
            rosterSignatureOf(listOf(user(uid = "uid-2"))),
        )
    }

    @Test
    fun `order matters — a reordered roster is a different roster`() {
        // Not a requirement in itself, but documents that the signature is
        // order-sensitive: callers must pass the server's own ordering
        val a = user(id = 1, uid = "a")
        val b = user(id = 2, uid = "b")
        assertNotEquals(rosterSignatureOf(listOf(a, b)), rosterSignatureOf(listOf(b, a)))
    }

    @Test
    fun `empty roster has a stable signature`() {
        assertEquals(rosterSignatureOf(emptyList()), rosterSignatureOf(emptyList()))
        assertNotEquals(rosterSignatureOf(emptyList()), rosterSignatureOf(listOf(user())))
    }

    // --- display signature (what the channel tree renders) ---

    @Test
    fun `display signature ignores talk flips too`() {
        val quiet = listOf(user(isTalking = false), user(id = 2, uid = "uid-2"))
        val talking = listOf(user(isTalking = true), user(id = 2, uid = "uid-2", isTalking = true))
        assertEquals(displaySignatureOf(quiet), displaySignatureOf(talking))
    }

    @Test
    fun `display signature tracks every painted flag`() {
        val base = listOf(user())
        val variants = listOf(
            listOf(user(nickname = "alice (mobile)")),
            listOf(user(isAway = true)),
            listOf(user(isRecording = true)),
            listOf(user(isInputMuted = true)),
            listOf(user(isOutputMuted = true)),
            listOf(user(hasInputHardware = false)),
            listOf(user(channelId = 11)),
            listOf(user(avatarId = "av-2")),
            listOf(user(), user(id = 2, uid = "uid-2")),
        )
        val expected = displaySignatureOf(base)
        for (variant in variants) {
            assertNotEquals(
                "a rendered change must invalidate the tree",
                expected,
                displaySignatureOf(variant),
            )
        }
    }
}
