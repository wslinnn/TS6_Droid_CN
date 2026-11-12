package dev.tsdroid.data

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The `ts3file://` link TeamSpeak clients exchange in chat text.
 *
 * Deliberately free of `android.net.Uri` so the build → parse round trip is
 * unit-testable: the receiver must honour the `path` query parameter (the
 * server-side directory), and dropping it made every file in a sub directory
 * impossible to download — the client asked the server for the bare name.
 */
object Ts3FileLink {

    data class Link(
        val host: String,
        val port: String,
        /** Normalized directory, always starts with '/'; "/" for the root. */
        val path: String,
        val channelId: Long,
        val fileName: String,
        val size: Long,
        val fileDateTime: Long?,
    )

    /** Build the link posted in chat. [path] is the file's directory. */
    fun build(
        host: String,
        port: String,
        channelId: Long,
        path: String,
        fileName: String,
        size: Long,
        fileDateTime: Long? = null,
    ): String {
        val dir = normalizePath(path) ?: "/"
        return "ts3file://${encode(host)}?port=${encode(port)}&channel=$channelId" +
            "&path=${encode(dir)}&filename=${encode(fileName)}&isDir=0&size=$size" +
            (fileDateTime?.let { "&fileDateTime=$it" } ?: "")
    }

    /** Parses the first `ts3file://` link in [text]; null when there is none. */
    fun parse(text: String): Link? {
        val start = text.indexOf("ts3file://")
        if (start < 0) return null
        val url = text.substring(start).takeWhile { !it.isWhitespace() }
        val rest = url.substringAfter("ts3file://")
        val host = rest.substringBefore('?')
        if (host.isEmpty()) return null
        val query = rest.substringAfter('?', "")
        return try {
            val params = parseQuery(query)
            val fileName = params["filename"]?.takeIf { it.isNotBlank() } ?: return null
            val path = normalizePath(params["path"]) ?: return null
            Link(
                host = decode(host),
                port = params["port"] ?: "",
                path = path,
                channelId = params["channel"]?.toLongOrNull() ?: 0L,
                fileName = fileName,
                size = params["size"]?.toLongOrNull() ?: 0L,
                fileDateTime = params["fileDateTime"]?.toLongOrNull(),
            )
        } catch (_: Exception) {
            // Malformed percent-encoding in a crafted link
            null
        }
    }

    /**
     * Normalize a directory path taken from a link or from the file browser:
     * missing/blank → root, empty segments collapsed, "." dropped.
     *
     * Returns null when any segment is ".." — a crafted link must not be able
     * to address anything outside the server's file area.
     */
    fun normalizePath(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return "/"
        val segments = value.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    /**
     * Absolute server-side path of the file a link points at, e.g.
     * ("/music", "song.mp3") → "/music/song.mp3". Null when either part is
     * unusable, so the caller can refuse to download instead of silently
     * asking the root directory for the name.
     */
    fun filePath(path: String, fileName: String): String? {
        val dir = normalizePath(path) ?: return null
        val name = fileName.trim().substringAfterLast('/').trim()
        if (name.isEmpty() || name == "." || name == "..") return null
        return (if (dir == "/") "/" else "$dir/") + name
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='))
            val value = decode(pair.substringAfter('=', ""))
            out[key] = value
        }
        return out
    }

    // URLEncoder/URLDecoder match what Uri.getQueryParameter does: '+' means
    // space and percent escapes cover everything else (CJK, '&', '=').
    private fun encode(raw: String): String = URLEncoder.encode(raw, "UTF-8")

    private fun decode(raw: String): String = URLDecoder.decode(raw, "UTF-8")
}
