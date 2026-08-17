package com.torfilx.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.AppSettings
import com.torfilx.core.model.StreamingMode
import com.torfilx.core.player.capability.DeviceCapabilitiesProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
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

        // "Buffer more first" trades a slower start for a steadier stream — the right call on a weak
        // or bursty connection where the default buffer underruns and stutters.
        val buffered = settings.streamingMode == StreamingMode.BUFFERED
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (buffered) MIN_BUFFER_MS * 2 else MIN_BUFFER_MS,
                if (buffered) MAX_BUFFER_MS * 2 else MAX_BUFFER_MS,
                if (buffered) BUFFER_FOR_PLAYBACK_MS * 2 else BUFFER_FOR_PLAYBACK_MS,
                if (buffered) BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS * 2 else BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            // Decoder fallback lets a stream that the first-choice decoder rejects retry on another,
            // instead of failing outright — cheap insurance across the many Fire TV SoCs.
            .setEnableDecoderFallback(true)
            .also {
                // When the user reports a device whose hardware decoder mangles or refuses a stream,
                // this makes Media3 try Android's software codecs first. They are always present, so
                // unlike bundled extensions this actually does something on a stock Fire TV.
                if (settings.forceSoftwareDecoder) {
                    it.setMediaCodecSelector(softwareFirstCodecSelector())
                }
            }

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
     * Media comes from the app.s own loopback torrent stream server, so the platform HTTP stack is
     * all that is needed — there is no server auth or connection pool to share.
     */
    @OptIn(UnstableApi::class)
    private fun dataSourceFactory(): DataSource.Factory = DefaultDataSource.Factory(context)

    /**
     * A codec selector that lists software decoders ahead of hardware ones.
     *
     * Android's software codecs are named `c2.android.*` (or `OMX.google.*` on older releases); we
     * detect them by name so this works from API 22 up, without the API 29 `hardwareAccelerated`
     * flag. Everything else is preserved in its original order, so decoder fallback still reaches the
     * hardware codec if software cannot handle the stream.
     */
    @OptIn(UnstableApi::class)
    private fun softwareFirstCodecSelector(): MediaCodecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
        infos.sortedByDescending { info ->
            val name = info.name.lowercase()
            name.startsWith("c2.android.") || name.startsWith("omx.google.")
        }
    }

    private companion object {
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 50_000
        const val BUFFER_FOR_PLAYBACK_MS = 2_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
        const val SEEK_INCREMENT_MS = 10_000L
    }
}
