package com.torfilx.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.AppSettings
import com.torfilx.core.network.di.MediaHttpClient
import com.torfilx.core.player.capability.DeviceCapabilitiesProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "PlayerFactory"

/**
 * Builds the ExoPlayer instance.
 *
 * Buffer sizes are tuned for a LAN, not the internet: the media is one Wi-Fi hop away, so a large
 * buffer only wastes heap on a 1.5 GB stick. Tunneling is enabled where the device supports it,
 * which is Amazon's recommendation for 4K/HDR (plan.md §7.1).
 */
@Singleton
class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    // The media client, not the API one: the API client rewrites request URLs to the configured
    // server (destroying absolute media URLs) and has a 15 s read timeout that would abort a stream.
    @MediaHttpClient private val okHttpClient: Provider<OkHttpClient>,
    private val capabilitiesProvider: DeviceCapabilitiesProvider,
) {

    @OptIn(UnstableApi::class)
    fun create(settings: AppSettings): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguage(settings.preferredAudioLanguage)
                .setPreferredTextLanguage(
                    settings.preferredSubtitleLanguage.takeIf { settings.subtitlesEnabledByDefault },
                )
                .setSelectUndeterminedTextLanguage(true)
                // Forced subtitles must always show, even when subtitles are "off": they carry the
                // translation of on-screen foreign dialogue.
                .setIgnoredTextSelectionFlags(0)
                .setTunnelingEnabled(
                    settings.tunneledPlayback && capabilitiesProvider.capabilities().supportsTunneledPlayback,
                )
                .build()
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            // Hardware decoders only: software fallback would silently produce a slideshow on a stick.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
            .also { TorfilxLog.i(TAG, "ExoPlayer created (tunneling=${settings.tunneledPlayback})") }
    }

    /**
     * Shares the app's OkHttp client so media requests carry the same auth header and connection
     * pool as the API — the media endpoints are on the same server.
     */
    @OptIn(UnstableApi::class)
    private fun dataSourceFactory(): DataSource.Factory {
        val http = OkHttpDataSource.Factory { request -> okHttpClient.get().newCall(request) }
        return DefaultDataSource.Factory(context, http)
    }

    private companion object {
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 50_000
        const val BUFFER_FOR_PLAYBACK_MS = 2_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
        const val SEEK_INCREMENT_MS = 10_000L
    }
}
