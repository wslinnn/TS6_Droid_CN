package dev.tsdroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServerErrorTextTest {

    @Test
    fun `parses the tslib server error prefix`() {
        val detail = ServerErrorText.parse("Server error: 524 - client is flooding")
        assertEquals(524, detail.code)
        assertEquals("client is flooding", detail.message)
    }

    @Test
    fun `parses a wrapped server error`() {
        val detail = ServerErrorText.parse("Connection failed: Server error: 1028 - invalid server password")
        assertEquals(1028, detail.code)
        assertEquals("invalid server password", detail.message)
    }

    @Test
    fun `keeps a plain exception message untouched`() {
        val detail = ServerErrorText.parse("Connection failed: timeout")
        assertNull(detail.code)
        assertEquals("Connection failed: timeout", detail.message)
    }

    @Test
    fun `blank input stays blank`() {
        val detail = ServerErrorText.parse(null)
        assertNull(detail.code)
        assertEquals("", detail.message)
    }

    @Test
    fun `known ids resolve to a resource`() {
        assertNotNull(ServerErrorText.resourceFor(524))
        assertNotNull(ServerErrorText.resourceFor(770))
        assertNotNull(ServerErrorText.resourceFor(781))
        assertNotNull(ServerErrorText.resourceFor(1027))
        assertNotNull(ServerErrorText.resourceFor(1028))
        assertNotNull(ServerErrorText.resourceFor(2568))
        assertNotNull(ServerErrorText.resourceFor(3329))
    }

    @Test
    fun `unknown and missing ids do not resolve`() {
        assertNull(ServerErrorText.resourceFor(9999))
        assertNull(ServerErrorText.resourceFor(null))
    }

    @Test
    fun `parsed detail survives an unknown code`() {
        val detail = ServerErrorText.parse("Server error: 9999 - something new")
        assertEquals(9999, detail.code)
        assertEquals("something new", detail.message)
        assertNull(ServerErrorText.resourceFor(detail.code))
    }
}
