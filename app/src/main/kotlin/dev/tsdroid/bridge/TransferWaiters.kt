package dev.tsdroid.bridge

import kotlinx.coroutines.CompletableDeferred

/**
 * Pending native file transfers, keyed by (channelId, path).
 *
 * Native transfer events identify the transfer by channel + path, and several
 * callers can legitimately wait for the same pair at once (two chat bubbles
 * for one image, a bubble plus its preview). Each key therefore holds a *set*
 * of waiters: an event completes all of them, and a caller that times out
 * removes only its own waiter.
 *
 * The previous single-slot tables (`path → channel`, `key → deferred`)
 * overwrote each other: the second request replaced the first, an event
 * completed only the last one, and the first caller's timeout cleanup then
 * deleted the surviving entry — both ended in a timeout, and a cross-channel
 * path collision delivered one channel's bytes to the other's waiter.
 */
class TransferWaiters<T : Any> {

    private val lock = Any()

    /** "channelId:path" → waiters. */
    private val waiters = HashMap<String, MutableSet<CompletableDeferred<T>>>()

    /**
     * path → channels with a request in flight. Only needed for events that
     * carry no channel id (`file_transfer_failed`).
     */
    private val pathChannels = HashMap<String, MutableSet<Long>>()

    /** Registers a waiter; the caller must [remove] it when it stops waiting. */
    fun register(channelId: Long, path: String): CompletableDeferred<T> {
        val deferred = CompletableDeferred<T>()
        synchronized(lock) {
            waiters.getOrPut(key(channelId, path)) { LinkedHashSet() }.add(deferred)
            pathChannels.getOrPut(path) { LinkedHashSet() }.add(channelId)
        }
        return deferred
    }

    /** Completes every waiter of (channelId, path). False when none waited. */
    fun complete(channelId: Long, path: String, value: T): Boolean =
        finish(channelId, path) { it.complete(value) }

    /** Fails every waiter of (channelId, path). False when none waited. */
    fun fail(channelId: Long, path: String, error: Throwable): Boolean =
        finish(channelId, path) { it.completeExceptionally(error) }

    /**
     * Completes every waiter on [path] regardless of channel. Used only when
     * an event lacks the channel id (older .so): delivering to all of them is
     * wrong for cross-channel path collisions, but strictly better than both
     * waiters timing out, and the current .so always sends channel_id.
     */
    fun completeAll(path: String, value: T): Boolean =
        finishAll(path) { it.complete(value) }

    /** Fails every waiter on [path] regardless of channel. */
    fun failAll(path: String, error: Throwable): Boolean =
        finishAll(path) { it.completeExceptionally(error) }

    /** Fails everything — used when the connection goes away. */
    fun reset(error: Throwable) {
        val pending = synchronized(lock) {
            val all = waiters.values.flatten()
            waiters.clear()
            pathChannels.clear()
            all
        }
        pending.forEach { it.completeExceptionally(error) }
    }

    /** Removes one waiter without touching its siblings. */
    fun remove(channelId: Long, path: String, waiter: CompletableDeferred<T>) {
        synchronized(lock) {
            val k = key(channelId, path)
            val set = waiters[k] ?: return
            set.remove(waiter)
            if (!set.isEmpty()) return
            waiters.remove(k)
            dropPathChannelLocked(channelId, path)
        }
    }

    private fun finish(channelId: Long, path: String, action: (CompletableDeferred<T>) -> Unit): Boolean {
        val k = key(channelId, path)
        val set: MutableSet<CompletableDeferred<T>>? = synchronized(lock) {
            val existing = waiters.remove(k)
            if (existing != null) dropPathChannelLocked(channelId, path)
            existing
        }
        if (set == null) return false
        set.forEach(action)
        return true
    }

    private fun finishAll(path: String, action: (CompletableDeferred<T>) -> Unit): Boolean {
        val set = synchronized(lock) {
            val channels = pathChannels.remove(path)?.toList().orEmpty()
            val all = ArrayList<CompletableDeferred<T>>()
            for (ch in channels) {
                waiters.remove(key(ch, path))?.let { all.addAll(it) }
            }
            all
        }
        if (set.isEmpty()) return false
        set.forEach(action)
        return true
    }

    private fun dropPathChannelLocked(channelId: Long, path: String) {
        val channels = pathChannels[path] ?: return
        channels.remove(channelId)
        if (channels.isEmpty()) pathChannels.remove(path)
    }

    private fun key(channelId: Long, path: String) = "$channelId:$path"
}
