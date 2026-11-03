package dev.tsdroid.bridge

/**
 * Opus packet TOC parsing (RFC 6716 §3.1), mirroring libopus packet-parse
 * semantics. Table lifted from the verified reference implementation in
 * MicYou's jitter buffer (`opus_packet_samples_48k`), expressed in µs.
 *
 * Used by receive-side diagnostics: the pipeline assumes 20ms frames; a
 * sender emitting other durations would be silently truncated by the fixed
 * 960-sample decode buffers, so the histogram must prove (or refute) that
 * assumption before any fix is designed.
 */
object OpusFrames {

    /**
     * Total audio duration of an Opus packet in µs, or null when unparseable
     * (empty, missing code-3 count byte, or a zero/malformed count).
     *
     * Code 2 (two frames of different configs) is approximated as 2× the
     * first frame's duration — close enough for histogram purposes; exact
     * parsing would require walking the embedded second TOC.
     */
    fun totalDurationUs(packet: ByteArray): Long? {
        if (packet.isEmpty()) return null
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameUs = when (config) {
            in 0..11 -> intArrayOf(10_000, 20_000, 40_000, 60_000)[config % 4] // SILK NB/MB/WB
            in 12..15 -> intArrayOf(10_000, 20_000)[config % 2]                // Hybrid SWB/FB
            else -> intArrayOf(2_500, 5_000, 10_000, 20_000)[config % 4]       // CELT NB/FB
        }
        val frames = when (toc and 0x3) {
            0 -> 1L
            1, 2 -> 2L
            else -> {
                if (packet.size < 2) return null
                val count = packet[1].toInt() and 0x3F
                if (count == 0) return null // libopus treats a zero count as malformed
                count.toLong()
            }
        }
        return frameUs * frames
    }
}
