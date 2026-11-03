package dev.tsdroid.bridge

import android.media.AudioRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * AudioRecord.stop()/release() can block indefinitely in native code on some
 * devices. Inline calls wedge their caller: the capture coroutine's rebuild
 * path, and worse — stopCapture on a caller thread while holding
 * captureSwapLock, deadlocking every later stop/rebuild behind the lock.
 *
 * Teardown therefore runs on a dedicated daemon thread and the caller's wait
 * is bounded: a recorder whose teardown hangs is abandoned (a leaked handle
 * is tolerated) instead of wedging the audio restart paths. Repeat requests
 * for an in-flight recorder join the same teardown — one stop thread per
 * recorder, never two.
 */
class RecorderStopper(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    companion object {
        /** Bounded wait for the daemon teardown; hanging stops are abandoned past this. */
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AudioRecord-stop").apply { isDaemon = true }
    }

    private val inFlight = ConcurrentHashMap<AudioRecord, Future<*>>()

    /**
     * Submit (or join the already in-flight) stop+release for [record] and
     * wait up to [timeoutMs]. Returns false when the teardown did not finish
     * in time — the recorder handle is then abandoned to the daemon thread
     * and the caller proceeds without it.
     */
    fun stopAndRelease(record: AudioRecord): Boolean {
        val future: Future<*> = synchronized(inFlight) {
            inFlight[record] ?: executor.submit {
                try {
                    record.stop()
                } catch (_: Throwable) {
                }
                try {
                    record.release()
                } catch (_: Throwable) {
                } finally {
                    inFlight.remove(record)
                }
            }.also { inFlight[record] = it }
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: ExecutionException) {
            // Teardown task completed; stop/release failures were swallowed inside
            true
        }
    }
}
