package com.myflix.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.myflix.core.common.log.MyflixLog
import com.myflix.core.data.settings.SettingsRepository
import com.myflix.core.data.sync.SyncScheduler
import com.myflix.core.player.PlaybackController
import com.myflix.core.player.display.FrameRateMatcher
import com.myflix.core.player.service.PlaybackService
import com.myflix.core.ui.theme.MyflixTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var playbackController: PlaybackController

    @Inject
    lateinit var frameRateMatcher: FrameRateMatcher

    private var frameRateApplied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        MyflixLog.debugEnabled = BuildConfig.DEBUG

        setContent {
            MyflixTheme {
                MyflixApp(onExitApp = { finish() })
            }
        }

        // Kick a background library refresh so Home is current by the time the user has read the
        // hero, without blocking the first frame.
        lifecycleScope.launch {
            if (settingsRepository.settings.first().isServerConfigured ||
                settingsRepository.demoMode.first()
            ) {
                syncScheduler.enqueueLibraryRefresh()
                syncScheduler.enqueueProgressSync()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The media session must exist while the app is in the foreground so the remote's transport
        // keys and Alexa reach the player (plan.md §7.1).
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
            .onFailure { MyflixLog.w(TAG, "Could not start playback service", it) }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            playbackController.stop(release = true)
        }
        if (frameRateApplied) {
            frameRateMatcher.reset(this)
            frameRateApplied = false
        }
    }

    /**
     * Applies display frame-rate matching for the content that is about to play.
     *
     * Called by the player screen through [PlaybackController]; kept on the activity because only a
     * window can request a display mode.
     */
    fun applyFrameRateMatching(contentFrameRate: Float?) {
        lifecycleScope.launch {
            if (!settingsRepository.settings.first().frameRateMatching) return@launch
            frameRateApplied = frameRateMatcher.matchFrameRate(this@MainActivity, contentFrameRate)
        }
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
