package dev.tsdroid.data

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory log mirror for the in-app log viewer (设置 → 更多 → 日志，仅 debug 包)。
 *
 * Diagnostic classes import this object aliased as `Log`
 * (`import dev.tsdroid.data.AppLog as Log`), so their existing
 * [Log.i]/[Log.d]/[Log.w]/[Log.e] call sites keep logging to logcat
 * AND capture into the ring buffer unchanged.
 *
 * Ring capture runs in debuggable builds only ([initCapture], called from
 * the Application): the viewer does not exist in release, so per-line
 * string building and snapshot-list writes there would be pure overhead.
 * Logcat passthrough is unconditional.
 */
object AppLog {

    private const val MAX_LINES = 2000

    /** Snapshot-able line list — Compose observable for the viewer. */
    val lines = mutableStateListOf<String>()

    /** Set once at app start; mirrors the debuggable gate that hides the viewer. */
    @Volatile
    var captureEnabled: Boolean = false
        private set

    fun initCapture(debuggable: Boolean) {
        captureEnabled = debuggable
    }

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun clear() = lines.clear()

    fun i(tag: String, msg: String) = log("I", tag, msg, null)
    fun d(tag: String, msg: String) = log("D", tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log("W", tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log("E", tag, msg, tr)

    private fun log(level: String, tag: String, msg: String, tr: Throwable?) {
        when (level) {
            "I" -> Log.i(tag, msg, tr)
            "D" -> Log.d(tag, msg, tr)
            "W" -> Log.w(tag, msg, tr)
            else -> Log.e(tag, msg, tr)
        }
        if (!captureEnabled) return
        // SimpleDateFormat is not thread-safe and log() runs on any thread
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = buildString {
            append(timestamp)
            append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (tr != null) {
                append('\n').append(Log.getStackTraceString(tr).trimEnd())
            }
        }
        synchronized(lines) {
            lines.add(line)
            while (lines.size > MAX_LINES) {
                lines.removeAt(0)
            }
        }
    }
}
