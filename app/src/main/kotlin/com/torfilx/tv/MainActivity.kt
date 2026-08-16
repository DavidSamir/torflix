package com.torfilx.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.player.PlaybackController
import com.torfilx.core.player.display.DisplayModeController
import com.torfilx.core.player.service.PlaybackService
import com.torfilx.core.ui.theme.TorfilxTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val TAG = "MainActivity"

/**
 * The single activity.
 *
 * `singleTask` + a single activity is what keeps focus state and the back stack coherent on a TV,
 * where the user re-enters the app from the launcher constantly (plan.md §2.3, §3).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var playbackController: PlaybackController

    @Inject
    lateinit var displayModeController: DisplayModeController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        TorfilxLog.debugEnabled = BuildConfig.DEBUG

        setContent {
            TorfilxTheme {
                TorfilxApp(onExitApp = { finish() })
            }
        }

    }

    override fun onStart() {
        super.onStart()
        displayModeController.attach(this)
        // The media session must exist while the app is in the foreground so the remote's transport
        // keys and Alexa reach the player (plan.md §7.1).
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
            .onFailure { TorfilxLog.w(TAG, "Could not start playback service", it) }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            playbackController.stop(release = true)
        }
        // Always hand the TV back its original display mode when we lose the screen.
        displayModeController.detach(this)
    }

    /**
     * Media keys are handled by the MediaSession, but Fire TV also delivers them here when no
     * session is active — forwarding them keeps play/pause working on Home.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (playbackController.player != null) {
                    playbackController.togglePlayPause()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }
}
