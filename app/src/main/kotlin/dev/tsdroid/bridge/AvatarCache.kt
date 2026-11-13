package dev.tsdroid.bridge

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.net.URLEncoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AvatarCache(private val cacheDir: File) {

    companion object {
        private const val TAG = "AvatarCache"
        private const val MAX_RETRIES = 5

        /**
         * How long a negative result is trusted. A user whose avatar download
         * failed during a network blip used to stay blank for the rest of the
         * process; retrying after a while (and after a reconnect clears the
         * cache) recovers without a restart. Also the retry interval the
         * view-model rescans at — its roster gate only re-scans on roster
         * changes, so the expiry needs a timer to be observed.
         */
        internal const val NEGATIVE_TTL_MS = 10 * 60 * 1000L

        /**
         * Upper bound for decoded avatars. Each one is small, but a busy
         * server can expose thousands of users over a long session; the
         * Compose-side map is bounded too, so this closes the loop on the
         * unbounded-cache problem. The least recently used entry is evicted
         * and re-read from the on-disk copy on demand.
         */
        private const val MAX_MEMORY_ENTRIES = 400
    }

    /** Attempt counter + timestamp for the negative cache. */
    private class Failures(var count: Int, var at: Long)

    /**
     * Keyed by uid **and** the server's avatar id: an avatar change must not
     * hit the old bitmap in memory or on disk. `|` cannot appear in a decoded
     * uid, so the pair is unambiguous.
     */
    private val memoryCacheLock = Any()
    private val memoryCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
            size > MAX_MEMORY_ENTRIES
    }
    private val loading = ConcurrentHashMap<String, Boolean>()
    private val failures = ConcurrentHashMap<String, Failures>()
    private val avatarsDir = File(cacheDir, "avatars").also { it.mkdirs() }

    private fun key(uid: String, avatarId: String?): String = "$uid|${avatarId.orEmpty()}"

    fun getAvatar(uid: String, avatarId: String?): ImageBitmap? {
        val k = key(uid, avatarId)
        return synchronized(memoryCacheLock) { memoryCache[k] }
    }

    fun hasNoAvatar(uid: String, avatarId: String?): Boolean {
        val k = key(uid, avatarId)
        val failure = failures[k] ?: return false
        if (failure.count < MAX_RETRIES) return false
        if (System.currentTimeMillis() - failure.at < NEGATIVE_TTL_MS) return true
        // Expired: drop it so this call reloads
        failures.remove(k)
        return false
    }

    /** Clear memory cache for specific UIDs so next loadAvatar forces re-download */
    fun clearMemoryCache(vararg uids: String) {
        for (uid in uids) {
            val prefix = "$uid|"
            synchronized(memoryCacheLock) {
                memoryCache.keys.filter { it.startsWith(prefix) }.forEach { memoryCache.remove(it) }
            }
            loading.keys.forEach { if (it.startsWith(prefix)) loading.remove(it) }
            failures.keys.forEach { if (it.startsWith(prefix)) failures.remove(it) }
        }
        Log.d(TAG, "Cleared memory cache for ${uids.size} UID(s)")
    }

    /** Clear ALL memory caches so next loadAvatar calls re-download */
    fun clearAllMemoryCache() {
        synchronized(memoryCacheLock) { memoryCache.clear() }
        loading.clear()
        failures.clear()
        Log.d(TAG, "Cleared ALL memory caches")
    }

    /**
     * Convert a TS3 base64 UID to the avatar file path used by the server.
     *
     * The server stores avatars at `/avatar_{encoded}` where each byte of the
     * decoded UID is encoded as two characters in [a-p] (nibble + 'a').
     */
    fun uidToAvatarPath(base64Uid: String): String {
        val bytes = Base64.decode(base64Uid, Base64.DEFAULT)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val unsigned = b.toInt() and 0xFF
            sb.append('a' + (unsigned shr 4))
            sb.append('a' + (unsigned and 0x0F))
        }
        return "/avatar_$sb"
    }

    suspend fun loadAvatar(uid: String, avatarId: String?, tsClient: TsClient) {
        if (uid.isEmpty()) return
        val k = key(uid, avatarId)
        if (synchronized(memoryCacheLock) { memoryCache.containsKey(k) }) return
        if (hasNoAvatar(uid, avatarId)) return
        if (loading.putIfAbsent(k, true) != null) return

        try {
            // Disk I/O and the JNI download must not run on the caller's
            // (main) dispatcher — both call sites collect from the UI side
            withContext(Dispatchers.IO) {
                // UID/avatar id contain / and = which are invalid in filenames
                val safeFileName = URLEncoder.encode(k, "UTF-8")
                val diskFile = File(avatarsDir, safeFileName)
                var bytes: ByteArray? = null
                var fromDisk = false

                // Try disk cache first
                if (diskFile.exists() && diskFile.length() > 0) {
                    bytes = diskFile.readBytes()
                    fromDisk = true
                    Log.d(TAG, "Read avatar from disk for $uid (${bytes.size} bytes)")
                }

                // Try decoding if we have disk data
                if (fromDisk && bytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        synchronized(memoryCacheLock) { memoryCache[k] = bitmap.asImageBitmap() }
                        Log.d(TAG, "Loaded avatar from disk cache for $uid")
                        return@withContext
                    }
                    Log.w(TAG, "Corrupt avatar on disk for $uid, deleting and re-downloading")
                    diskFile.delete()
                    bytes = null
                }

                // Download from server using UID-based path
                val path = uidToAvatarPath(uid)
                Log.d(TAG, "Downloading avatar for $uid at path $path")
                bytes = tsClient.downloadFile(0L, path)

                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "Download returned empty/null for $uid (attempt ${(failures[k]?.count ?: 0) + 1}/$MAX_RETRIES)")
                    recordFailure(k)
                    return@withContext
                }

                Log.d(TAG, "Downloaded avatar for $uid: ${bytes.size} bytes")

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    diskFile.writeBytes(bytes)
                    synchronized(memoryCacheLock) { memoryCache[k] = bitmap.asImageBitmap() }
                    Log.i(TAG, "Avatar loaded for $uid")
                } else {
                    Log.w(TAG, "Failed to decode downloaded avatar for $uid (${bytes.size} bytes)")
                    recordFailure(k)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load avatar for $uid", e)
            recordFailure(k)
        } finally {
            loading.remove(k)
        }
    }

    private fun recordFailure(k: String) {
        val now = System.currentTimeMillis()
        failures.compute(k) { _, existing ->
            if (existing == null) Failures(1, now) else existing.also {
                it.count++
                it.at = now
            }
        }
    }
}
