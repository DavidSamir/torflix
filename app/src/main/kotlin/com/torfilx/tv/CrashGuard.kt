package com.torfilx.tv

import android.app.Application
import android.content.Intent
import com.torfilx.core.common.log.CrashStore
import com.torfilx.core.common.log.TorfilxLog
import kotlin.system.exitProcess

private const val TAG = "CrashGuard"

/**
 * Last line of defence against Android's "TORFILX keeps stopping" dialog.
 *
 * On a TV that dialog is a dead end: there is no notification shade, no way to report it, and the
 * remote's only useful button is the one that closes the app. So an uncaught exception is recorded
 * — durably, via [CrashStore], because the in-memory log does not survive the process being killed —
 * and the app restarts itself at Home instead of dying in front of the viewer.
 *
 * A restart loop is worse than a crash dialog, so restarting is abandoned when a crash recurs
 * within [RESTART_WINDOW_MS] (a fast loop) or after [MAX_CONSECUTIVE_CRASHES] crashes without the
 * app ever staying up long enough to be declared stable (a slow, steady loop). The consecutive
 * counter is reset from the UI once the app has run cleanly for [STABILITY_MS] (see MainActivity).
 */
internal fun Application.installCrashGuard(crashStore: CrashStore) {
    val platformHandler = Thread.getDefaultUncaughtExceptionHandler()

    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        val now = System.currentTimeMillis()
        val prefs = crashPrefs()
        val lastCrashAt = prefs.getLong(KEY_LAST_CRASH_AT, 0L)
        val consecutive = prefs.getInt(KEY_CONSECUTIVE, 0) + 1
        val fastLoop = now - lastCrashAt < RESTART_WINDOW_MS
        val steadyLoop = consecutive >= MAX_CONSECUTIVE_CRASHES
        val giveUp = fastLoop || steadyLoop

        runCatching {
            crashStore.record(thread.name, error)
            TorfilxLog.e(TAG, "Uncaught exception on ${thread.name} (#$consecutive, giveUp=$giveUp)", error)
            // commit(), not apply(): the process is killed a few lines below, and an asynchronous
            // write never lands — which would make every crash look like the first and turn the
            // restart into an endless loop (the app flickering once a second on the TV).
            prefs.edit()
                .putLong(KEY_LAST_CRASH_AT, now)
                .putInt(KEY_CONSECUTIVE, consecutive)
                .commit()
        }

        if (giveUp) {
            // Restarting will not help; let the platform show its dialog rather than loop forever.
            platformHandler?.uncaughtException(thread, error)
            return@setDefaultUncaughtExceptionHandler
        }

        val restart = runCatching {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        }.getOrNull()

        if (restart != null) {
            runCatching { startActivity(restart) }
                .onFailure { platformHandler?.uncaughtException(thread, error) }
        }

        // The process is in an undefined state after an uncaught exception; end it cleanly so the
        // restarted task begins fresh.
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(EXIT_CODE)
    }
}

/** Called once the app has run cleanly for a while, so a past crash streak no longer counts. */
internal fun Application.markRunStable() {
    runCatching { crashPrefs().edit().putInt(KEY_CONSECUTIVE, 0).commit() }
}

private fun Application.crashPrefs() =
    getSharedPreferences("torfilx_crash", android.content.Context.MODE_PRIVATE)

private const val KEY_LAST_CRASH_AT = "last_crash_at"
private const val KEY_CONSECUTIVE = "consecutive_crashes"
private const val RESTART_WINDOW_MS = 10_000L
private const val MAX_CONSECUTIVE_CRASHES = 5
internal const val STABILITY_MS = 120_000L
private const val EXIT_CODE = 10
