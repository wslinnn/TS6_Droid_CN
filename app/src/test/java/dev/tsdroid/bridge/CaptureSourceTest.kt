package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSourceTest {

    @Test
    fun `from maps raw values and falls back to voice performance`() {
        assertEquals(CaptureSource.VOICE_COMMUNICATION, CaptureSource.from("voice_communication"))
        assertEquals(CaptureSource.MIC, CaptureSource.from("mic"))
        assertEquals(CaptureSource.VOICE_PERFORMANCE, CaptureSource.from("voice_performance"))
        assertEquals(CaptureSource.VOICE_PERFORMANCE, CaptureSource.from(null))
        assertEquals(CaptureSource.VOICE_PERFORMANCE, CaptureSource.from("bogus"))
    }

    @Test
    fun `voice performance falls back to mic below api 29`() {
        assertEquals(9, CaptureSource.VOICE_PERFORMANCE.resolveSourceId(29))
        assertEquals(9, CaptureSource.VOICE_PERFORMANCE.resolveSourceId(34))
        assertEquals(1, CaptureSource.VOICE_PERFORMANCE.resolveSourceId(28))
        assertEquals(1, CaptureSource.VOICE_PERFORMANCE.resolveSourceId(21))
    }

    @Test
    fun `other sources keep their ids on all api levels`() {
        assertEquals(7, CaptureSource.VOICE_COMMUNICATION.resolveSourceId(21))
        assertEquals(1, CaptureSource.MIC.resolveSourceId(21))
    }
}
