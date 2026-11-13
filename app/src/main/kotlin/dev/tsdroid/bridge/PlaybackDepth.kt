package dev.tsdroid.bridge

/**
 * Write-side depth of the [android.media.AudioTrack]: how much PCM is still
 * queued inside the track, in milliseconds.
 *
 * Depth is `frames written` − `frames played`, and
 * [android.media.AudioTrack.getPlaybackHeadPosition] is the only source for
 * the second half — but it is a **signed 32-bit** frame counter. Read as a
 * signed Long it turns negative after 2³¹ frames (≈ 12.4 h of continuous
 * playback at 48 kHz), the difference explodes to a huge positive number,
 * the watermark below then matches on every single frame and the output goes
 * permanently silent until the track is rebuilt.
 *
 * So the raw counter is read unsigned and every 32-bit wrap is added back as
 * a constant offset, keeping one monotonic frame timeline for both halves.
 * The signed value is what the platform hands out; this class owns the
 * unwrapping so no call site has to remember it.
 *
 * Not thread-safe; owned by the playback loop.
 */
internal class PlaybackDepth(private val sampleRate: Int = AudioBridge.SAMPLE_RATE) {

    private var written = 0L
    private var lastRaw = 0L

    /** Completed 32-bit wraps of the head counter, as a frame offset. */
    private var headBase = 0L

    /** Number of 32-bit counter wraps absorbed (diagnostics only). */
    var wraps = 0
        private set

    /** Start a new timeline — the counter restarts at 0 with a fresh track. */
    fun reset() {
        written = 0L
        lastRaw = 0L
        headBase = 0L
    }

    /** Total PCM frames handed to the track since [reset]. */
    fun onFramesWritten(count: Int) {
        written += count
    }

    /**
     * Frames still queued inside the track after the counter read [rawHead]
     * (the signed value straight from the platform), in milliseconds.
     * Negative values mean the read raced ahead of the write counter and are
     * clamped to zero.
     */
    fun bufferedMs(rawHead: Int): Long {
        val raw = rawHead.toLong() and 0xFFFF_FFFFL
        if (raw < lastRaw) {
            if (lastRaw > HALF_WRAP) {
                // The counter wrapped: continue the same timeline past 2^32
                headBase += ONE_WRAP
                wraps++
            } else {
                // Counter went backwards mid-range: the track was flushed or
                // recreated underneath us. Restart the timeline at this read
                // instead of folding a phantom 24.8 h into the depth.
                headBase = 0L
                written = raw
            }
        }
        lastRaw = raw
        val played = headBase + raw
        val queued = written - played
        return (if (queued < 0L) 0L else queued) * 1000L / sampleRate
    }

    private companion object {
        const val ONE_WRAP = 0x1_0000_0000L
        const val HALF_WRAP = 0x8000_0000L
    }
}
