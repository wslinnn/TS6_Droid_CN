package dev.tslib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The snapshot POJOs are rebuilt by the JNI bridge on every refresh; without
 * value semantics every StateFlow assignment counts as a change.
 */
class SnapshotValueSemanticsTest {

    private fun channel(
        id: Long = 7,
        name: String = "Lobby",
        permissionHints: Long = 0,
    ) = Channel(
        id, 1L, name, "topic", "desc", 3L, true, false, false, false,
        4, 10, 32, 64, 0, 12L, permissionHints, true,
    )

    private fun serverInfo(name: String = "srv") = ServerInfo(
        name, "Linux", "3.13", 32, 4, 9, 3600L, "welcome", 42L,
    )

    private fun user(
        id: Int = 5,
        nickname: String = "alice",
        talking: Boolean = false,
        groups: LongArray = longArrayOf(6, 8),
    ) = User(
        id, "uid==", 99L, 7L, nickname, 0, talking, false, false, true, true,
        false, false, false, false, false, 0, "", groups, 3L, "Android", "1.0",
        "DE", "desc", "avatar", 11L, 1L, 2L,
    )

    @Test
    fun `equal channels stay equal`() {
        assertEquals(channel(), channel())
        assertEquals(channel().hashCode(), channel().hashCode())
        assertNotEquals(channel(), channel(name = "Other"))
        assertNotEquals(channel(), channel(permissionHints = 64))
    }

    @Test
    fun `equal server info stays equal`() {
        assertEquals(serverInfo(), serverInfo())
        assertEquals(serverInfo().hashCode(), serverInfo().hashCode())
        assertNotEquals(serverInfo(), serverInfo(name = "other"))
    }

    @Test
    fun `user equality covers talk state and group array`() {
        assertEquals(user(), user())
        assertEquals(user().hashCode(), user().hashCode())
        assertNotEquals(user(), user(talking = true))
        assertNotEquals(user(), user(groups = longArrayOf(6)))
        assertNotEquals(user(), user(nickname = "bob"))
    }

    @Test
    fun `list equality deduplicates refreshed snapshots`() {
        val before = listOf(channel(), channel(id = 8, name = "B"))
        val after = listOf(channel(), channel(id = 8, name = "B"))
        assertEquals(before, after)
    }
}
