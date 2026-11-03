package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpusFramesTest {

    private fun duration(vararg bytes: Int): Long? {
        val packet = ByteArray(bytes.size) { bytes[it].toByte() }
        return OpusFrames.totalDurationUs(packet)
    }

    @Test
    fun `silk durations per config`() {
        assertEquals(10_000L, duration(0x00))                       // config 0, NB 10ms
        assertEquals(20_000L, duration(0x08))                       // config 1, NB 20ms
        assertEquals(60_000L, duration(0x18))                       // config 3, NB 60ms
        assertEquals(20_000L, duration(0x28))                       // config 5, WB 20ms
    }

    @Test
    fun `hybrid durations are 10 or 20 ms only`() {
        assertEquals(10_000L, duration(12 shl 3))                   // config 12, SWB 10ms
        assertEquals(20_000L, duration(13 shl 3))                   // config 13, SWB 20ms
        assertEquals(10_000L, duration(14 shl 3))                   // config 14, FB 10ms
        assertEquals(20_000L, duration(15 shl 3))                   // config 15, FB 20ms
    }

    @Test
    fun `celt durations down to 2_5 ms`() {
        assertEquals(2_500L, duration(16 shl 3))                    // config 16, NB 2.5ms
        assertEquals(5_000L, duration(17 shl 3))                    // config 17, NB 5ms
        assertEquals(20_000L, duration(19 shl 3))                   // config 19, NB 20ms
        assertEquals(2_500L, duration(28 shl 3))                    // config 28, FB 2.5ms
    }

    @Test
    fun `code 1 doubles the config duration`() {
        assertEquals(40_000L, duration(0x08 or 0x01))               // 2 × SILK 20ms
        assertEquals(10_000L, duration((17 shl 3) or 0x01))         // 2 × CELT 5ms
        assertEquals(5_000L, duration((16 shl 3) or 0x01))          // 2 × CELT 2.5ms
    }

    @Test
    fun `code 3 count byte controls total duration`() {
        assertEquals(60_000L, duration(0x08 or 0x03, 3))            // 3 × SILK 20ms
        assertEquals(20_000L, duration(0x08 or 0x03, 1))
        assertEquals(120_000L, duration(0x08 or 0x03, 6))
    }

    @Test
    fun `code 3 with zero count or missing count byte is malformed`() {
        assertNull(duration(0x08 or 0x03, 0))
        assertNull(duration(0x08 or 0x03))
    }

    @Test
    fun `empty packet is unparseable`() {
        assertNull(OpusFrames.totalDurationUs(ByteArray(0)))
    }

    @Test
    fun `stereo flag does not change duration`() {
        assertEquals(20_000L, duration(0x08 or 0x04))               // s=1 (stereo), code 0
        assertEquals(40_000L, duration(0x08 or 0x04 or 0x01))       // stereo, code 1 → 2 × 20ms
    }
}
