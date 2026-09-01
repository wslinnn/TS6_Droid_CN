package dev.tsdroid.bridge

import android.content.Context
import dev.tslib.Identity
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Access to the persistent TeamSpeak identity at filesDir/identity.ini
 * (standard TS3 identity format, interchangeable with desktop clients).
 * All methods do blocking IO/JNI — call from a background dispatcher.
 */
class IdentityFileStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, "identity.ini")

    fun exists(): Boolean = file.exists()

    /** @return the UID of the stored identity, or null if none exists/it is invalid */
    fun readUniqueId(): String? {
        if (!file.exists()) return null
        val identity = try {
            Identity.load(file.absolutePath)
        } catch (_: Throwable) {
            return null
        }
        return try {
            identity.uniqueId
        } catch (_: Throwable) {
            null
        } finally {
            identity.close()
        }
    }

    /** Copy the identity file content to [out]. @return false when no identity exists */
    fun exportTo(out: OutputStream): Boolean {
        if (!file.exists()) return false
        file.inputStream().use { input -> input.copyTo(out) }
        return true
    }

    /**
     * Validate [input] as an identity, then replace the stored one. The new
     * identity takes effect on the next connection.
     *
     * @return true when a valid identity was imported
     */
    fun importFrom(input: InputStream): Boolean {
        val data = input.use { it.readBytes().toString(Charsets.UTF_8) }
        if (data.isBlank()) return false
        val identity = try {
            Identity.fromString(data)
        } catch (_: Throwable) {
            return false
        }
        val uid = try {
            identity.uniqueId
        } catch (_: Throwable) {
            null
        } finally {
            identity.close()
        }
        if (uid.isNullOrBlank()) return false

        val tmp = File(context.filesDir, "identity.import.tmp")
        return try {
            tmp.writeText(data, Charsets.UTF_8)
            if (file.exists()) file.delete()
            if (tmp.renameTo(file)) true else false
        } catch (_: Exception) {
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
