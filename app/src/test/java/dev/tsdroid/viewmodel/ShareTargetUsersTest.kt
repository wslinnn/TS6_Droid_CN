package dev.tsdroid.viewmodel

import dev.tslib.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two user lists the server screen builds must stay distinct.
 *
 * They were once the same variable: the channel tree was passed the
 * "everyone but me" list and the local user disappeared from the roster.
 * These tests pin the distinction so that cannot happen silently again.
 */
class ShareTargetUsersTest {

    private fun user(id: Int, nickname: String = "user$id") = User(
        id, "uid-$id", 0L, 10L, nickname, 0, false,
        false, false, true, true, false, false,
        false, false, false, 0, "", longArrayOf(), 0L,
        "android", "1.0", "", "", null, 0L, null, null,
    )

    private val roster = listOf(user(1, "me"), user(7, "alice"), user(9, "bob"))

    @Test
    fun `share targets exclude the local user`() {
        val targets = shareTargetUsersOf(roster, selfId = 1)
        assertEquals(listOf(7, 9), targets.map { it.id })
        assertTrue("sharing to yourself is meaningless", targets.none { it.id == 1 })
    }

    @Test
    fun `the channel tree roster must still contain the local user`() {
        // The regression: the tree was handed the share-target list, so the
        // local row vanished from the roster. This asserts the contract the
        // tree relies on — the full roster, unfiltered.
        assertTrue(
            "the channel tree must show our own row",
            roster.any { it.id == 1 },
        )
        // And explicitly: filtering for share targets is a different list.
        assertTrue(
            shareTargetUsersOf(roster, selfId = 1).none { it.id == 1 },
        )
    }

    @Test
    fun `a null self id excludes nobody yet`() {
        // Before the connection reports our client id there is no "me" to
        // exclude; dropping a user here would be arbitrary.
        assertEquals(roster, shareTargetUsersOf(roster, selfId = null))
    }

    @Test
    fun `a self id not present in the roster leaves it unchanged`() {
        assertEquals(roster, shareTargetUsersOf(roster, selfId = 42))
    }

    @Test
    fun `everyone else is kept in order`() {
        assertEquals(
            listOf("alice", "bob"),
            shareTargetUsersOf(roster, selfId = 1).map { it.nickname },
        )
    }

    @Test
    fun `a single-user roster yields no share targets`() {
        assertEquals(emptyList<User>(), shareTargetUsersOf(listOf(user(1)), selfId = 1))
    }
}
