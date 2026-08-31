package dev.tsdroid.bridge

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap

class FileCache(private val context: Context) {

    companion object {
        private const val TAG = "FileCache"
        private const val BASE_FOLDER = "TS6 Mobile"
    }

    private val contentResolver = context.contentResolver

    /**
     * Retourne le fichier depuis le cache local, ou null s'il n'existe pas.
     * Cherche dans Documents/TS6 Mobile/{serverHost}/{relativePath}
     */
    fun get(serverHost: String, relativePath: String): ByteArray? {
        val uri = findFileUri(serverHost, relativePath) ?: return null
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached file $relativePath", e)
            null
        }
    }

    /**
     * Enregistre les bytes dans Documents/TS6 Mobile/{serverHost}/{relativePath}.
     * Crée le fichier via MediaStore. Écrase si existe déjà.
     */
    fun put(serverHost: String, relativePath: String, data: ByteArray) {
        try {
            // Delete existing file if any
            findFileUri(serverHost, relativePath)?.let {
                contentResolver.delete(it, null, null)
            }

            val fileName = relativePath.substringAfterLast('/')
            val subDir = relativePath.substringBeforeLast('/', "")
            val relPath = buildString {
                append("Documents/$BASE_FOLDER/")
                append(serverHost)
                if (subDir.isNotEmpty()) {
                    append("/")
                    append(subDir)
                }
            }

            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"

            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relPath)
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }

            val collection = MediaStore.Files.getContentUri("external")
            val uri = contentResolver.insert(collection, values) ?: run {
                Log.w(TAG, "Failed to insert file into MediaStore: $relativePath")
                return
            }

            contentResolver.openOutputStream(uri)?.use { it.write(data) }

            values.clear()
            values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache $relativePath", e)
        }
    }

    /**
     * Vérifie si le fichier existe dans le cache.
     */
    fun exists(serverHost: String, relativePath: String): Boolean {
        return findFileUri(serverHost, relativePath) != null
    }

    fun getUri(serverHost: String, relativePath: String): android.net.Uri? {
        return findFileUri(serverHost, relativePath)
    }

    private fun findFileUri(serverHost: String, relativePath: String): android.net.Uri? {
        val fileName = relativePath.substringAfterLast('/')
        val subDir = relativePath.substringBeforeLast('/', "")
        val relPath = buildString {
            append("Documents/$BASE_FOLDER/")
            append(serverHost)
            if (subDir.isNotEmpty()) {
                append("/")
                append(subDir)
            }
            append("/")
        }

        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, relPath)

        return try {
            contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    ContentUris.withAppendedId(collection, id)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query MediaStore for $relativePath", e)
            null
        }
    }
}
