package dev.tsdroid.bridge

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class IconCache(private val cacheDir: File) {

    companion object {
        private const val TAG = "IconCache"
    }

    private val memoryCache = ConcurrentHashMap<Long, ImageBitmap>()
    private val loading = ConcurrentHashMap<Long, Boolean>()
    private val iconsDir = File(cacheDir, "icons").also { it.mkdirs() }

    fun getIcon(iconId: Long): ImageBitmap? = memoryCache[iconId]

    /** Drops the decoded icons; the on-disk copies stay warm. */
    fun clearMemoryCache() {
        memoryCache.clear()
        loading.clear()
        Log.d(TAG, "Cleared icon memory cache")
    }

    fun hasCached(iconId: Long): Boolean =
        memoryCache.containsKey(iconId) || File(iconsDir, iconId.toString()).exists()

    suspend fun loadIcon(iconId: Long, tsClient: TsClient) {
        if (iconId == 0L) return
        if (memoryCache.containsKey(iconId)) return
        if (loading.putIfAbsent(iconId, true) != null) return

        try {
            // Try disk cache first
            val diskFile = File(iconsDir, iconId.toString())
            var bytes: ByteArray? = null

            if (diskFile.exists() && diskFile.length() > 0) {
                bytes = diskFile.readBytes()
            } else {
                // Download from server: icons are on channel 0, path /icon_{id}
                val path = "/icon_$iconId"
                bytes = tsClient.downloadFile(0L, path)
                if (bytes != null && bytes.isNotEmpty()) {
                    diskFile.writeBytes(bytes)
                }
            }

            if (bytes != null && bytes.isNotEmpty()) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    memoryCache[iconId] = bitmap.asImageBitmap()
                } else {
                    Log.w(TAG, "Failed to decode icon $iconId (${bytes.size} bytes)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load icon $iconId", e)
        } finally {
            loading.remove(iconId)
        }
    }
}
