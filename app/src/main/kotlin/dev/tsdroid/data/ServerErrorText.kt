package dev.tsdroid.data

import android.content.Context
import dev.tsdroid.han.R

/**
 * Localized presentation for TeamSpeak server errors.
 *
 * Two shapes reach the UI:
 * - command failures carry the numeric error id plus the server's English text
 *   (native `command_error` event);
 * - connect/browse failures arrive as a `TsLibException` whose message is
 *   `"Server error: {id} - {text}"` (see tslib-core `Error::Server`).
 *
 * Known ids map to resources; anything unrecognized is shown verbatim so the
 * diagnostic text is never lost. Ids come from TeamSpeak's `public_errors.h`.
 */
object ServerErrorText {

    private val CODE_RESOURCES: Map<Int, Int> = mapOf(
        524 to R.string.error_server_flooding,
        770 to R.string.error_server_already_in_channel,
        781 to R.string.error_server_channel_password,
        1027 to R.string.error_server_full,
        1028 to R.string.error_server_password,
        2568 to R.string.error_server_permission,
        2572 to R.string.error_server_permission,
        3329 to R.string.error_server_banned,
    )

    private val SERVER_ERROR_PREFIX =
        Regex("""Server error:\s*(\d+)\s*-\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

    data class Detail(val code: Int?, val message: String)

    /** Split a `TsLibException` message into its server code and text. */
    fun parse(raw: String?): Detail {
        val text = raw?.trim().orEmpty()
        val match = SERVER_ERROR_PREFIX.find(text)
        return if (match != null) {
            Detail(match.groupValues[1].toIntOrNull(), match.groupValues[2])
        } else {
            Detail(null, text)
        }
    }

    /** @return the string resource for [code], or null when unmapped. */
    fun resourceFor(code: Int?): Int? = code?.let { CODE_RESOURCES[it] }

    /**
     * Localized text for [code]/[message], or the trimmed message when the id
     * is unknown. Callers supply their own fallback for an empty result.
     */
    fun localized(context: Context, code: Int?, message: String?): String {
        resourceFor(code)?.let { return context.getString(it) }
        return message?.trim().orEmpty()
    }

    fun localized(context: Context, raw: String?): String {
        val detail = parse(raw)
        return localized(context, detail.code, detail.message)
    }
}
