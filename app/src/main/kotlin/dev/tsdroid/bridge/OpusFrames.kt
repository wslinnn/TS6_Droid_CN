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

    /** Histogram granularity — every Opus frame duration is a multiple of 2.5 ms. */
    const val BUCKET_US = 2_500L

    /** Histogram slots covering 0–200 ms; the last slot also collects anything above. */
    const val BUCKETS = 81

    /**
     * Total audio duration of an Opus packet in µs, or null when unparseable
     * (empty, missing code-3 count byte, or a zero/malformed count).
     *
     * Code 2 (two frames of different configs) is approximated as 2× the
     * first frame's duration — close enough for histogram purposes; exact
     * parsing would require walking the embedded second TOC.
     *
     * Runs once per received packet on the native pump thread, so the tables
     * are spelled out as constants: an `intArrayOf(...)` per call would be an
     * allocation per frame per speaker in the hottest path there is.
     */
    fun totalDurationUs(packet: ByteArray): Long? {
        if (packet.isEmpty()) return null
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameUs = when {
            config < 12 -> when (config and 0x3) { // SILK NB/MB/WB
                0 -> 10_000L
                1 -> 20_000L
                2 -> 40_000L
                else -> 60_000L
            }
            config < 16 -> if (config and 0x1 == 0) 10_000L else 20_000L // Hybrid SWB/FB
            else -> when (config and 0x3) { // CELT NB/WB/SWB/FB
                0 -> 2_500L
                1 -> 5_000L
                2 -> 10_000L
                else -> 20_000L
            }
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

    /** Histogram slot for [durationUs] at [BUCKET_US] granularity. */
    fun bucketOf(durationUs: Long): Int =
        (durationUs / BUCKET_US).toInt().coerceIn(0, BUCKETS - 1)
}
