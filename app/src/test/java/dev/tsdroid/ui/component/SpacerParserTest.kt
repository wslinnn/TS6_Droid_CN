package dev.tsdroid.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpacerParserTest {

    @Test
    fun `center spacer parses with text`() {
        val info = parseSpacer("[cspacer]Welcome")
        assertEquals(SpacerType.CENTER, info?.type)
        assertEquals("Welcome", info?.displayText)
    }

    @Test
    fun `left spacer without text parses`() {
        val info = parseSpacer("[lspacer]─")
        assertEquals(SpacerType.LEFT, info?.type)
        assertEquals("─", info?.displayText)
    }

    @Test
    fun `left spacer defaults for bare tag`() {
        val info = parseSpacer("[spacer]")
        assertEquals(SpacerType.LEFT, info?.type)
        assertEquals("", info?.displayText)
    }

    @Test
    fun `right spacer parses`() {
        val info = parseSpacer("[rspacer]Rules")
        assertEquals(SpacerType.RIGHT, info?.type)
        assertEquals("Rules", info?.displayText)
    }

    @Test
    fun `repeat spacer parses`() {
        val info = parseSpacer("[*spacer]======")
        assertEquals(SpacerType.REPEAT, info?.type)
        assertEquals("======", info?.displayText)
    }

    @Test
    fun `parser is case insensitive`() {
        assertEquals(SpacerType.CENTER, parseSpacer("[CSpAcEr]hi")?.type)
        assertEquals(SpacerType.REPEAT, parseSpacer("[*SPACER]x")?.type)
    }

    @Test
    fun `regular channel names are not spacers`() {
        assertNull(parseSpacer("Lobby"))
        assertNull(parseSpacer(" поговорим"))
    }
}
