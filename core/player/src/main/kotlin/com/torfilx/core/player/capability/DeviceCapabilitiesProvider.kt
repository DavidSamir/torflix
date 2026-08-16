package com.torfilx.core.player.capability

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.core.content.getSystemService
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.DeviceCapabilities
import com.torfilx.core.model.HdrType
import com.torfilx.core.model.VideoDecoderCapability
import com.torfilx.core.model.VideoMimeTypes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Capabilities"

/**
 * Reports what this specific Fire TV can decode and display.
 *
 * The report is sent to the server so it can pick direct play or transcode (plan.md §7.2). It is
 * computed from the platform (`MediaCodecList`, `Display.getHdrCapabilities`, `AudioManager`) rather
 * than from a device-model allowlist, because Fire OS versions differ in what they expose even on
 * the same hardware.
 */
@Singleton
class DeviceCapabilitiesProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cached: DeviceCapabilities? = null

    /** Cached for the process lifetime; codecs cannot change without an OS update (and a reboot). */
    fun capabilities(): DeviceCapabilities = cached ?: compute().also { cached = it }

    fun invalidate() {
        cached = null
    }

    private fun compute(): DeviceCapabilities = try {
        val decoders = videoDecoders()
        val display = displayInfo()
        DeviceCapabilities(
            videoDecoders = decoders,
            audioCodecs = audioCodecs(),
            passthroughCodecs = passthroughCodecs(),
            maxAudioChannels = maxChannels(),
            displayHdrTypes = display.hdrTypes,
            maxDisplayWidth = display.width,
            maxDisplayHeight = display.height,
            supportedRefreshRates = display.refreshRates,
            supportsTunneledPlayback = supportsTunneling(decoders),
        )
    } catch (error: Exception) {
        TorfilxLog.e(TAG, "Capability detection failed; using conservative defaults", error)
        DeviceCapabilities.CONSERVATIVE
    }

    private fun videoDecoders(): List<VideoDecoderCapability> {
        val interesting = listOf(
            VideoMimeTypes.H264,
            VideoMimeTypes.HEVC,
            VideoMimeTypes.VP9,
            VideoMimeTypes.AV1,
            VideoMimeTypes.MPEG4,
            VideoMimeTypes.DOLBY_VISION,
        )
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val result = mutableMapOf<String, VideoDecoderCapability>()

        codecList.codecInfos.forEach { info ->
            if (info.isEncoder) return@forEach
            info.supportedTypes.forEach { type ->
                val mime = type.lowercase()
                if (mime !in interesting) return@forEach
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: return@forEach
                val video = caps.videoCapabilities ?: return@forEach

                val maxWidth = runCatching { video.supportedWidths.upper }.getOrDefault(1920)
                val maxHeight = runCatching { video.supportedHeights.upper }.getOrDefault(1080)
                val maxFps = runCatching { video.supportedFrameRates.upper }.getOrDefault(60)
                val profiles = caps.profileLevels.map { it.profile }.toSet()

                val existing = result[mime]
                // Keep the most capable decoder when several are reported for one MIME type.
                if (existing == null || maxWidth * maxHeight > existing.maxWidth * existing.maxHeight) {
                    result[mime] = VideoDecoderCapability(
                        mimeType = mime,
                        maxWidth = maxWidth,
                        maxHeight = maxHeight,
                        maxFrameRate = maxFps,
                        profiles = profiles,
                    )
                }
            }
        }
        TorfilxLog.i(TAG, "Video decoders: ${result.keys.joinToString()}")
        return result.values.toList()
    }

    private fun audioCodecs(): Set<String> {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val decoded = mutableSetOf<String>()
        codecList.codecInfos.forEach { info ->
            if (info.isEncoder) return@forEach
            info.supportedTypes.forEach { type ->
                when (type.lowercase()) {
                    "audio/mp4a-latm" -> decoded += "aac"
                    "audio/mpeg" -> decoded += "mp3"
                    "audio/ac3" -> decoded += "ac3"
                    "audio/eac3", "audio/eac3-joc" -> decoded += "eac3"
                    "audio/vorbis" -> decoded += "vorbis"
                    "audio/opus" -> decoded += "opus"
                    "audio/flac" -> decoded += "flac"
                    "audio/raw" -> decoded += "pcm"
                    "audio/vnd.dts", "audio/vnd.dts.hd" -> decoded += "dts"
                }
            }
        }
        return decoded
    }

    /**
     * Codecs the HDMI sink accepts as a bitstream.
     *
     * This is what decides whether an AC3/E-AC3/DTS track can be sent untouched to an AV receiver
     * instead of being transcoded by the server (plan.md §15 question 1).
     */
    private fun passthroughCodecs(): Set<String> {
        val audioManager = context.getSystemService<AudioManager>() ?: return emptySet()
        val result = mutableSetOf<String>()
        runCatching {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.forEach { device ->
                device.encodings.forEach { encoding ->
                    when (encoding) {
                        android.media.AudioFormat.ENCODING_AC3 -> result += "ac3"
                        android.media.AudioFormat.ENCODING_E_AC3 -> result += "eac3"
                        android.media.AudioFormat.ENCODING_DTS -> result += "dts"
                        android.media.AudioFormat.ENCODING_DTS_HD -> result += "dts-hd"
                        else -> Unit
                    }
                }
            }
        }.onFailure { TorfilxLog.w(TAG, "Passthrough detection failed", it) }
        return result
    }

    private fun maxChannels(): Int {
        val audioManager = context.getSystemService<AudioManager>() ?: return 2
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .flatMap { it.channelCounts.toList() }
                .maxOrNull() ?: 2
        }.getOrDefault(2)
    }

    private data class DisplayInfo(
        val width: Int,
        val height: Int,
        val hdrTypes: Set<HdrType>,
        val refreshRates: List<Float>,
    )

    @Suppress("DEPRECATION")
    private fun displayInfo(): DisplayInfo {
        val displayManager = context.getSystemService<android.hardware.display.DisplayManager>()
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return DisplayInfo(1920, 1080, emptySet(), emptyList())

        val modes = runCatching { display.supportedModes.toList() }.getOrDefault(emptyList())
        val maxWidth = modes.maxOfOrNull { it.physicalWidth } ?: 1920
        val maxHeight = modes.maxOfOrNull { it.physicalHeight } ?: 1080
        val refreshRates = modes.map { it.refreshRate }.distinct().sorted()

        val hdrTypes = mutableSetOf<HdrType>()
        runCatching {
            display.hdrCapabilities?.supportedHdrTypes?.forEach { type ->
                when (type) {
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> hdrTypes += HdrType.HDR10
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> hdrTypes += HdrType.HDR10_PLUS
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> hdrTypes += HdrType.DOLBY_VISION
                    Display.HdrCapabilities.HDR_TYPE_HLG -> hdrTypes += HdrType.HLG
                }
            }
        }.onFailure { TorfilxLog.w(TAG, "HDR capability query failed", it) }

        return DisplayInfo(maxWidth, maxHeight, hdrTypes, refreshRates)
    }

    /**
     * Tunneled playback needs OS support and a decoder that advertises it. Amazon recommends it for
     * 4K/HDR on Fire OS 7+, where it avoids a frame-copy through the app's surface.
     */
    private fun supportsTunneling(decoders: List<VideoDecoderCapability>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { type ->
                    val mime = type.lowercase()
                    (mime == VideoMimeTypes.HEVC || mime == VideoMimeTypes.H264) &&
                        runCatching {
                            info.getCapabilitiesForType(type)
                                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)
                        }.getOrDefault(false)
                }
            } && decoders.isNotEmpty()
        }.getOrDefault(false)
    }
}
