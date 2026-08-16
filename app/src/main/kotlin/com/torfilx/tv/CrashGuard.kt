package com.torfilx.tv

import android.app.Application
import android.content.Intent
import com.torfilx.core.common.log.TorfilxLog
import kotlin.system.exitProcess

private const val TAG = "CrashGuard"

/**
 * Last line of defence against Android's "TORFILX keeps stopping" dialog.
 *
 * On a TV that dialog is a dead end: there is no notification shade, no way to report it, and the
 * remote's only useful button is the one that closes the app. So an uncaught exception is logged
 * (the log survives in the in-memory buffer and can be exported from Settings) and the app restarts
 * itself at Home instead of dying in front of the viewer.
 *
 * A restart loop would be worse than a crash dialog, so a second crash within [RESTART_WINDOW_MS]
 * hands control back to the platform handler.
 */
internal fun Application.installCrashGuard() {
    val platformHandler = Thread.getDefaultUncaughtExceptionHandler()

    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        val now = System.currentTimeMillis()
        val lastCrashAt = crashPrefs().getLong(KEY_LAST_CRASH_AT, 0L)
        val isRepeatCrash = now - lastCrashAt < RESTART_WINDOW_MS

        runCatching {
            TorfilxLog.e(TAG, "Uncaught exception on ${thread.name} (repeat=$isRepeatCrash)", error)
            crashPrefs().edit().putLong(KEY_LAST_CRASH_AT, now).apply()
        }

        if (isRepeatCrash) {
            // Crashing again straight after a restart means restarting will not help.
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

private fun Application.crashPrefs() =
    getSharedPreferences("torfilx_crash", android.content.Context.MODE_PRIVATE)

private const val KEY_LAST_CRASH_AT = "last_crash_at"
private const val RESTART_WINDOW_MS = 10_000L
private const val EXIT_CODE = 10
