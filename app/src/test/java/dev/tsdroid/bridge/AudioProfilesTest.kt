package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioProfilesTest {

    @Test
    fun `q10 maps to the 60k ceiling`() {
        assertEquals(60_000, AudioProfiles.resolve(10).bitrate)
    }

    @Test
    fun `linear per-quality mapping`() {
        assertEquals(48_000, AudioProfiles.resolve(8).bitrate)
        assertEquals(42_000, AudioProfiles.resolve(7).bitrate)
        assertEquals(30_000, AudioProfiles.resolve(5).bitrate)
    }

    @Test
    fun `degenerate quality clamps to the intelligibility floor`() {
        assertEquals(16_000, AudioProfiles.resolve(2).bitrate)
        assertEquals(16_000, AudioProfiles.resolve(0).bitrate)
        assertEquals(16_000, AudioProfiles.resolve(-1).bitrate)
    }

    @Test
    fun `fec is always on with fixed expected loss`() {
        val p = AudioProfiles.resolve(8)
        assertEquals(true, p.fecEnabled)
        assertEquals(10, p.expectedPacketLossPercent)
    }
}
