package com.torfilx.core.common.log

import android.util.Log
import java.util.ArrayDeque
import java.util.Locale

/**
 * Logging with a bounded in-memory ring buffer.
 *
 * There is no crash reporter on Fire OS (no Google Play Services, plan.md §12), so diagnosing a
 * problem on the actual stick means reading logs from the device. Settings → "Export logs" dumps
 * this buffer, which is why every log call also lands here and not only in logcat.
 */
object TorfilxLog {

    private const val MAX_ENTRIES = 500
    private const val TAG_PREFIX = "Torfilx"

    data class Entry(
        val timestampMs: Long,
        val level: Int,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
    )

    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)

    /** Verbose/debug logs are suppressed in release builds; the app sets this at startup. */
    @Volatile
    var debugEnabled: Boolean = false

    fun v(tag: String, message: String) = log(Log.VERBOSE, tag, message, null)
    fun d(tag: String, message: String) = log(Log.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Log.INFO, tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(Log.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(Log.ERROR, tag, message, throwable)

    private fun log(level: Int, tag: String, message: String, throwable: Throwable?) {
        if (!debugEnabled && level <= Log.DEBUG) return
        val entry = Entry(System.currentTimeMillis(), level, tag, message, throwable)
        synchronized(buffer) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
        }
        val fullTag = "$TAG_PREFIX/$tag"
        if (throwable != null) Log.println(level, fullTag, "$message\n${Log.getStackTraceString(throwable)}")
        else Log.println(level, fullTag, message)
    }

    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    /** Human-readable dump used by Settings → Export logs. */
    fun dump(): String = buildString {
        snapshot().forEach { entry ->
            append(formatTimestamp(entry.timestampMs))
            append(' ')
            append(levelChar(entry.level))
            append('/')
            append(entry.tag)
            append(": ")
            append(entry.message)
            append('\n')
            entry.throwable?.let {
                append(Log.getStackTraceString(it))
                append('\n')
            }
        }
    }

    private fun levelChar(level: Int): Char = when (level) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        else -> '?'
    }

    // A full date, not just time-of-day: a log kept over a session used to wrap at 24 h, so events
    // could not be ordered across a day boundary and a crash could not be dated.
    private val timestampFormat = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun formatTimestamp(ms: Long): String =
        synchronized(timestampFormat) { timestampFormat.format(java.util.Date(ms)) }
}
