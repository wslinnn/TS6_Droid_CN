package dev.tsdroid.bridge

import java.util.ArrayDeque

/**
 * Bounded FIFO of encoded frames for one user. When the queue is full the
 * OLDEST frame is evicted to admit the new one: in a live conversation the
 * freshest speech matters more than the stalest, and the queue must never
 * grow without bound. Thread-safe via an internal lock.
 *
 * Frames are tagged with the sender's per-packet sequence (u16, wraps at
 * 2^16); each [Entry] records how many frames went missing immediately
 * before it so the playback loop can conceal the loss — the frame right
 * before a gap is recoverable from the FEC data embedded in the entry's
 * frame (see [OpusCodec.decode]).
 */
class FrameQueue(private val capacity: Int) {

    /** A queued frame plus the loss state of the frames immediately before it. */
    class Entry(val frame: ByteArray, val gap: Int, val enqueuedAt: Long)

    private val queue = ArrayDeque<Entry>()

    /** Sender sequence of the most recently offered frame; -1 = none yet. */
    private var lastSeq = -1

    /**
     * Enqueue a frame, evicting the oldest one when [capacity] is reached.
     *
     * @param seq sender's per-packet sequence number (wraps at 2^16)
     * @param nowMs arrival time on a **monotonic** clock, in the same timeline
     *     the consumer passes to [oldestAgeMs] (wall clock is not used here on
     *     purpose: an NTP/user time change would otherwise show up as a
     *     negative or hour-long frame age)
     */
    fun offer(frame: ByteArray, seq: Int, nowMs: Long) {
        synchronized(this) {
            val gap = if (lastSeq < 0) {
                GAP_RESET
            } else {
                // Wrap-around-safe distance from the previous frame
                val diff = (seq - lastSeq + 65536) % 65536
                when {
                    diff == 0 -> 0 // duplicate — play anyway
                    diff <= MAX_CONCEAL_GAP + 1 -> diff - 1
                    else -> GAP_RESET // jump too wide: new talkspurt / reconnect
                }
            }
            lastSeq = seq
            if (queue.size >= capacity) queue.removeFirst()
            queue.addLast(Entry(frame, gap, nowMs))
        }
    }

    /** Dequeue the oldest entry, or null when empty. */
    fun pollFirst(): Entry? = synchronized(this) { queue.pollFirst() }

    /** Age of the oldest queued frame, or [Long.MAX_VALUE] when empty. */
    fun oldestAgeMs(now: Long): Long = synchronized(this) {
        val first = queue.firstOrNull() ?: return Long.MAX_VALUE
        now - first.enqueuedAt
    }

    /**
     * Advance the sequence tracker for a packet that carries no playable
     * audio (comfort-noise marker during open-mic silence). The marker
     * occupies a sequence number, so the next real frame's loss inference is
     * measured from here instead of reporting the marker as a lost frame.
     */
    fun skip(seq: Int) {
        synchronized(this) { lastSeq = seq }
    }

    fun clear() = synchronized(this) {
        queue.clear()
        // Whatever comes after a purge (focus gap, output mute) has no
        // sequence continuity with what came before — start loss inference
        // over rather than concealing across the hole
        lastSeq = -1
    }

    val size: Int
        @Synchronized get() = queue.size

    companion object {
        /** No loss can be inferred (first frame, or after a discontinuity). */
        const val GAP_RESET = -1

        /** Gaps wider than this are treated as a fresh start, not as loss. */
        const val MAX_CONCEAL_GAP = 16
    }
}
