package dev.tsdroid.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object InAppUpdater {
    private const val TAG = "InAppUpdater"

    enum class DownloadState {
        IDLE, DOWNLOADING, DONE, FAILED
    }

    data class DownloadProgress(
        val state: DownloadState = DownloadState.IDLE,
        val progress: Float = 0f,
        val error: String? = null,
    ) {
        companion object {
            /** Sentinel for "total size unknown" (chunked transfer). */
            const val UNKNOWN_PROGRESS = -1f
        }
    }

    /**
     * Downloads the release APK and hands it to the system installer.
     *
     * @param expectedSize size advertised by the release API (0 = unknown).
     *   The download is rejected when the received byte count differs: the
     *   system installer verifies the signature, but a truncated file or an
     *   injected/replaced one with a matching length prefix would otherwise be
     *   installed blindly.
     */
    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        fileName: String = "update.apk",
        expectedSize: Long = 0L,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress(DownloadProgress(DownloadState.DOWNLOADING, 0f))

            val cacheDir = File(context.cacheDir, "updates").also { it.mkdirs() }
            val apkFile = File(cacheDir, fileName)
            // Never reuse a previous attempt's leftovers (partial file from a
            // failed download used to stay there and confuse later runs)
            if (apkFile.exists()) apkFile.delete()

            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000

            // contentLength is -1 when the server streams chunked; only
            // report progress when the total size is actually known
            val totalSize = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val progress = if (totalSize > 0) {
                            (downloaded.toFloat() / totalSize).coerceIn(0f, 1f)
                        } else {
                            DownloadProgress.UNKNOWN_PROGRESS
                        }
                        onProgress(DownloadProgress(DownloadState.DOWNLOADING, progress))
                    }
                }
            }
            conn.disconnect()

            // A 200 with a short body (or a proxy-injected truncation) is not
            // detectable by the installer — compare against both sizes
            if (totalSize > 0 && downloaded != totalSize) {
                throw IllegalStateException(
                    "Update download truncated: $downloaded/$totalSize bytes",
                )
            }
            if (expectedSize > 0 && downloaded != expectedSize) {
                throw IllegalStateException(
                    "Update size mismatch: got $downloaded bytes, release advertises $expectedSize",
                )
            }

            onProgress(DownloadProgress(DownloadState.DOWNLOADING, 1f))

            installApk(context, apkFile)

            onProgress(DownloadProgress(DownloadState.DONE))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed", e)
            // Never leave a partial/foreign APK behind for the next attempt
            runCatching { File(File(context.cacheDir, "updates"), fileName).delete() }
            onProgress(DownloadProgress(DownloadState.FAILED, error = e.message ?: "下载失败"))
            false
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun clearCache(context: Context) {
        val cacheDir = File(context.cacheDir, "updates")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }
}
