package com.torfilx.core.common.log

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable crash reports.
 *
 * The in-memory [TorfilxLog] buffer is destroyed when the crash guard kills the process, so the one
 * stack trace worth having would otherwise be gone before the viewer could export it. This writes
 * each crash to app-private storage — with the device, OS and app context needed to diagnose it —
 * so it survives the restart and rides along in Settings → Export logs.
 *
 * There is no remote reporter on Fire OS (no Play Services), so a user-sent export is the channel;
 * this makes that export actually contain the crash.
 */
@Singleton
class CrashStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val dir: File by lazy { File(context.filesDir, "crashes").apply { mkdirs() } }

    /** Persists one crash. Cheap and self-contained: safe to call from the uncaught-exception path. */
    fun record(threadName: String, error: Throwable) {
        runCatching {
            trimTo(MAX_REPORTS - 1)
            File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(report(threadName, error))
        }
    }

    /** Every retained crash report, newest first, ready to append to a log export. */
    fun readAll(): String = runCatching {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) "" else files.joinToString("\n\n") { it.readText() }
    }.getOrDefault("")

    /** Wall-clock time of the most recent crash, or null if there is none. */
    fun lastCrashAtMs(): Long? =
        dir.listFiles()?.maxOfOrNull { it.lastModified() }?.takeIf { it > 0 }

    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun report(threadName: String, error: Throwable): String = buildString {
        appendLine("=== TORFILX crash ===")
        appendLine("time:    ${isoTime(System.currentTimeMillis())}")
        appendLine("app:     ${appVersion()}")
        appendLine("device:  ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
        appendLine(
            "android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "abi ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
        )
        appendLine("thread:  $threadName")
        appendLine()
        append(Log.getStackTraceString(error))
    }

    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.versionCode})"
    }.getOrDefault("unknown")

    private fun isoTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    /** Keeps the newest [keep] reports, deleting the rest. */
    private fun trimTo(keep: Int) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep.coerceAtLeast(0)).forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val MAX_REPORTS = 5
    }
}
