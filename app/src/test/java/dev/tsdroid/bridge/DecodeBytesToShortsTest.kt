package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeBytesToShortsTest {

    @Test
    fun `converts little-endian 16-bit samples`() {
        val out = ShortArray(4)
        decodeBytesToShorts(byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x7F), out)
        assertEquals(1, out[0].toInt())
        assertEquals(0x7FFF, out[1].toInt())
    }

    @Test
    fun `zeroes the tail beyond the decoded sample count`() {
        val out = ShortArray(6) { 7 }
        decodeBytesToShorts(byteArrayOf(1, 0, 2, 0), out) // 2 samples in, 6-sample buffer
        assertEquals(1, out[0].toInt())
        assertEquals(2, out[1].toInt())
        for (i in 2..5) {
            assertEquals("stale sample at $i must be cleared", 0, out[i].toInt())
        }
    }

    @Test
    fun `clips input longer than the output buffer`() {
        val out = ShortArray(2)
        decodeBytesToShorts(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0), out)
        assertEquals(1, out[0].toInt())
        assertEquals(2, out[1].toInt())
    }

    @Test
    fun `short decode fully clears the buffer for the next user`() {
        // decodeBuffer is shared across users in the mixer: residue from a
        // long frame must not leak into a subsequent user's short frame
        val out = ShortArray(4)
        decodeBytesToShorts(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9), out)
        decodeBytesToShorts(byteArrayOf(1, 0), out)
        assertEquals(1, out[0].toInt())
        assertEquals(0, out[1].toInt())
        assertEquals(0, out[2].toInt())
        assertEquals(0, out[3].toInt())
    }
}
