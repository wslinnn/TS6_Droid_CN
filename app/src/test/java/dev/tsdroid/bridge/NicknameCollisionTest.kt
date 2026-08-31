package dev.tsdroid.bridge

import dev.tslib.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NicknameCollisionTest {

    private fun user(id: Int, nickname: String) = User(
        id, null, 0L, 0L, nickname, 0,
        false, false, false, true, true,
        false, false, false, false, false,
        0, null, longArrayOf(), 0L,
        null, null, null, null, null, 0L,
    )

    @Test
    fun `no users means no collision`() {
        assertFalse(hasNicknameCollision(emptyArray(), 5, "alice"))
        assertFalse(hasNicknameCollision(null, 5, "alice"))
    }

    @Test
    fun `collision when another user matches case-insensitively`() {
        val users = arrayOf(user(7, "ALICE"), user(9, "bob"))
        assertTrue(hasNicknameCollision(users, 5, "alice"))
    }

    @Test
    fun `own entry does not count as collision`() {
        val users = arrayOf(user(5, "alice"))
        assertFalse(hasNicknameCollision(users, 5, "alice"))
    }

    @Test
    fun `without own client id a single match stays ambiguous`() {
        // Without knowing our own client id a lone match could be us, so the
        // implementation conservatively reports no collision
        val users = arrayOf(user(7, "alice"))
        assertFalse(hasNicknameCollision(users, null, "alice"))
    }

    @Test
    fun `unrelated nicknames never collide`() {
        val users = arrayOf(user(7, "bob"), user(8, "carol"))
        assertFalse(hasNicknameCollision(users, 5, "alice"))
    }

    @Test
    fun `suffix appends attempt number`() {
        assertEquals("alice", nicknameWithCollisionSuffix("alice", 0))
        assertEquals("alice1", nicknameWithCollisionSuffix("alice", 1))
        assertEquals("alice12", nicknameWithCollisionSuffix("alice", 12))
    }
}
