package dev.tsdroid.bridge

/**
 * Static per-channel encoder profile: a pure table lookup from the channel's
 * codec quality to encoder settings. There is deliberately NO runtime
 * adaptation — the TS server trims audio packets that exceed the channel's
 * per-quality budget (empirical anchor on q10: 60 kbps survives, 64 kbps is
 * trimmed), so the safest strategy is to match the budget conservatively and
 * let always-on inband FEC handle the weak-link case.
 */
object AudioProfiles {

    /** Per-quality bitrate step: 6000 × quality fits the known q10 anchor. */
    private const val BITRATE_PER_QUALITY = 6_000

    /** Absolute ceiling — matches the measured q10 server budget. */
    private const val BITRATE_MAX = 60_000

    /** Speech intelligibility floor for degenerate quality values. */
    private const val BITRATE_MIN = 16_000

    /**
     * Expected packet loss the encoder optimizes its FEC redundancy for.
     * Kept constant: on clean links the overhead is small, on lossy ones the
     * redundancy is already in place (same static posture as WebRTC's
     * default always-on inband FEC).
     */
    const val EXPECTED_PACKET_LOSS_PERCENT = 10

    data class AudioProfile(
        val bitrate: Int,
        val fecEnabled: Boolean = true,
        val expectedPacketLossPercent: Int = EXPECTED_PACKET_LOSS_PERCENT,
    )

    /** Resolve the profile for a channel codec quality (0-10, TS server value). */
    fun resolve(quality: Int): AudioProfile {
        val bitrate = (BITRATE_PER_QUALITY * quality).coerceIn(BITRATE_MIN, BITRATE_MAX)
        return AudioProfile(bitrate)
    }
}
