package com.torfilx.tv

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.torfilx.core.common.log.TorfilxLog
import kotlinx.coroutines.launch

/**
 * Debug-only control surface, driven by intent extras.
 *
 * This is how the demo library and injected failures are switched on from a script or from `adb`
 * during development and UI testing (plan.md §12, "debug tooling"). It is compiled in for debug
 * builds only — `BuildConfig.DEBUG` gates every branch — so a release build ignores these extras.
 *
 * Usage:
 *   adb shell am start -n com.torfilx.tv.debug/com.torfilx.tv.MainActivity --ez torfilx_demo true
 */
internal fun MainActivity.handleDebugIntent(intent: Intent?) {
    if (!BuildConfig.DEBUG || intent == null) return

    if (intent.hasExtra(EXTRA_DEMO_MODE)) {
        val enabled = intent.getBooleanExtra(EXTRA_DEMO_MODE, false)
        TorfilxLog.i("Debug", "Demo library set to $enabled via intent extra")
        lifecycleScope.launch { settingsRepository.setDemoMode(enabled) }
    }

    if (intent.hasExtra(EXTRA_DEMO_MEDIA)) {
        val url = intent.getStringExtra(EXTRA_DEMO_MEDIA)
        TorfilxLog.i("Debug", "Demo media source overridden")
        demoRemoteSource.mediaUrlOverride = url?.takeIf { it.isNotBlank() }
    }

    if (intent.hasExtra(EXTRA_SERVER_URL)) {
        val url = intent.getStringExtra(EXTRA_SERVER_URL).orEmpty()
        TorfilxLog.i("Debug", "Server URL set via intent extra")
        lifecycleScope.launch { settingsRepository.setServerUrl(url) }
    }
}

private const val EXTRA_DEMO_MODE = "torfilx_demo"
private const val EXTRA_SERVER_URL = "torfilx_server"
private const val EXTRA_DEMO_MEDIA = "torfilx_demo_media"
