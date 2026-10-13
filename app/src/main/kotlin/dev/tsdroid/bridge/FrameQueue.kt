package dev.tsdroid.bridge

import java.util.ArrayDeque

/**
 * Bounded FIFO of encoded frames for one user. When the queue is full the
 * OLDEST frame is evicted to admit the new one: in a live conversation the
 * freshest speech matters more than the stalest, and the queue must never
 * grow without bound. Thread-safe via an internal lock.
 */
class FrameQueue(private val capacity: Int) {

    private val queue = ArrayDeque<ByteArray>()

    /** Enqueue a frame, evicting the oldest one when [capacity] is reached. */
    fun offer(frame: ByteArray) {
        synchronized(this) {
            if (queue.size >= capacity) {
                queue.removeFirst()
            }
            queue.addLast(frame)
        }
    }

    /** Dequeue the oldest frame, or null when empty. */
    fun pollFirst(): ByteArray? = synchronized(this) { queue.pollFirst() }

    fun clear() = synchronized(this) { queue.clear() }

    val size: Int
        @Synchronized get() = queue.size
}
