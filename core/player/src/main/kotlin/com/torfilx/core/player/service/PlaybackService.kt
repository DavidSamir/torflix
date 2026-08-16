package com.torfilx.core.player.service

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.player.PlaybackController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val TAG = "PlaybackService"

/**
 * Publishes the player as a `MediaSession`.
 *
 * This is what makes the remote's transport keys **and Alexa** ("Alexa, pause", "rewind 30 seconds")
 * control playback: on Fire TV those commands are delivered to the active media session, not to the
 * foreground activity (plan.md §7.1).
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playbackController: PlaybackController

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val player = playbackController.ensurePlayer()
            mediaSession = MediaSession.Builder(this, player).build()
            TorfilxLog.i(TAG, "Media session created")
        }.onFailure { TorfilxLog.e(TAG, "Could not create media session", it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * The app was swiped away from the launcher's recents. A TV video app should not keep playing
     * invisibly, so playback stops — but only after the position has been saved (plan.md §7.6).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        playbackController.stop(release = false)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
