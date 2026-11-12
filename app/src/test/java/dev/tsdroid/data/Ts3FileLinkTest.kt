package dev.tsdroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ts3FileLinkTest {

    @Test
    fun `round trip keeps sub directory and encoded name`() {
        val url = Ts3FileLink.build(
            host = "ts.example.com",
            port = "9987",
            channelId = 42L,
            path = "/music/album 1",
            fileName = "song & chorus [中文].mp3",
            size = 1234L,
            fileDateTime = 1700000000L,
        )
        val link = Ts3FileLink.parse("看看这个 $url 谢谢")!!
        assertEquals("ts.example.com", link.host)
        assertEquals("9987", link.port)
        assertEquals(42L, link.channelId)
        assertEquals("/music/album 1", link.path)
        assertEquals("song & chorus [中文].mp3", link.fileName)
        assertEquals(1234L, link.size)
        assertEquals(1700000000L, link.fileDateTime)
    }

    @Test
    fun `missing path defaults to root`() {
        val link = Ts3FileLink.parse("ts3file://host?port=9987&channel=1&filename=a.png&size=9")!!
        assertEquals("/", link.path)
    }

    @Test
    fun `path from legacy links without the parameter is root`() {
        val link = Ts3FileLink.parse(
            "ts3file://host?port=9987&channel=1&filename=a.png&isDir=0&size=9",
        )!!
        assertEquals("/", link.path)
        assertEquals("/a.png", Ts3FileLink.filePath(link.path, link.fileName))
    }

    @Test
    fun `sub directory is honoured by filePath`() {
        val link = Ts3FileLink.parse(
            "ts3file://host?port=9987&channel=1&path=%2Fmusic&filename=a.mp3&size=9",
        )!!
        assertEquals("/music", link.path)
        assertEquals("/music/a.mp3", Ts3FileLink.filePath(link.path, link.fileName))
    }

    @Test
    fun `traversal segment rejects the link`() {
        val link = Ts3FileLink.parse(
            "ts3file://host?port=9987&channel=1&path=%2Fmusic%2F..%2F..%2Fetc&filename=passwd",
        )
        assertNull(link)
        assertNull(Ts3FileLink.normalizePath("/a/../../b"))
        // A name is reduced to its last segment, so it cannot escape either
        assertEquals("/secret", Ts3FileLink.filePath("/", "../secret"))
        assertNull(Ts3FileLink.filePath("/", ".."))
    }

    @Test
    fun `normalization collapses duplicate and trailing separators`() {
        assertEquals("/music/album", Ts3FileLink.normalizePath("//music//album/"))
        assertEquals("/music", Ts3FileLink.normalizePath("music/./"))
        assertEquals("/", Ts3FileLink.normalizePath(""))
        assertEquals("/", Ts3FileLink.normalizePath(null))
    }

    @Test
    fun `malformed encoding does not throw`() {
        assertNull(Ts3FileLink.parse("ts3file://host?filename=%E0%A4%A"))
    }

    @Test
    fun `non ts3file text is not a link`() {
        assertNull(Ts3FileLink.parse("hello world"))
        assertNull(Ts3FileLink.parse("ts3file://host?channel=1"))
    }
}
