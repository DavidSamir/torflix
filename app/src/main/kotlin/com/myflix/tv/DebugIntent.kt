package com.myflix.tv

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.myflix.core.common.log.MyflixLog
import kotlinx.coroutines.launch

/**
 * Debug-only control surface, driven by intent extras.
 *
 * This is how the demo library and injected failures are switched on from a script or from `adb`
 * during development and UI testing (plan.md §12, "debug tooling"). It is compiled in for debug
 * builds only — `BuildConfig.DEBUG` gates every branch — so a release build ignores these extras.
 *
 * Usage:
 *   adb shell am start -n com.myflix.tv.debug/com.myflix.tv.MainActivity --ez myflix_demo true
 */
internal fun MainActivity.handleDebugIntent(intent: Intent?) {
    if (!BuildConfig.DEBUG || intent == null) return

    if (intent.hasExtra(EXTRA_DEMO_MODE)) {
        val enabled = intent.getBooleanExtra(EXTRA_DEMO_MODE, false)
        MyflixLog.i("Debug", "Demo library set to $enabled via intent extra")
        lifecycleScope.launch { settingsRepository.setDemoMode(enabled) }
    }

    if (intent.hasExtra(EXTRA_SERVER_URL)) {
        val url = intent.getStringExtra(EXTRA_SERVER_URL).orEmpty()
        MyflixLog.i("Debug", "Server URL set via intent extra")
        lifecycleScope.launch { settingsRepository.setServerUrl(url) }
    }
}

private const val EXTRA_DEMO_MODE = "myflix_demo"
private const val EXTRA_SERVER_URL = "myflix_server"
