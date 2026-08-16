package com.torfilx.core.model

/** A video decoder the device actually reports through `MediaCodecList` (plan.md §7.2). */
data class VideoDecoderCapability(
    val mimeType: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Int,
    /** Profile numbers as reported by `MediaCodecInfo.CodecProfileLevel`. */
    val profiles: Set<Int> = emptySet(),
    val supportsHdr10: Boolean = false,
    val supportsHdr10Plus: Boolean = false,
    val supportsDolbyVision: Boolean = false,
    val supportsHlg: Boolean = false,
)

/**
 * What this particular Fire TV can decode and output. Sent to the server so it can decide between
 * direct play and transcoding; the app never guesses (plan.md §7.2).
 */
data class DeviceCapabilities(
    val videoDecoders: List<VideoDecoderCapability> = emptyList(),
    val audioCodecs: Set<String> = emptySet(),
    /** Codecs the HDMI sink accepts as bitstream (AC3/E-AC3/DTS passthrough). */
    val passthroughCodecs: Set<String> = emptySet(),
    val maxAudioChannels: Int = 2,
    val displayHdrTypes: Set<HdrType> = emptySet(),
    val maxDisplayWidth: Int = 1920,
    val maxDisplayHeight: Int = 1080,
    val supportedRefreshRates: List<Float> = emptyList(),
    val supportsTunneledPlayback: Boolean = false,
) {
    fun supportsVideo(mimeType: String, width: Int?, height: Int?): Boolean {
        val decoder = videoDecoders.firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }
            ?: return false
        val w = width ?: return true
        val h = height ?: return true
        // Decoders report their maxima in the natural orientation; accept either.
        return (w <= decoder.maxWidth && h <= decoder.maxHeight) ||
            (h <= decoder.maxWidth && w <= decoder.maxHeight)
    }

    fun supportsHdr(type: HdrType): Boolean = type == HdrType.NONE || displayHdrTypes.contains(type)

    fun supportsAudio(codec: String): Boolean =
        audioCodecs.any { it.equals(codec, ignoreCase = true) } ||
            passthroughCodecs.any { it.equals(codec, ignoreCase = true) }

    companion object {
        /** Conservative fallback used before the real report is computed: 1080p H.264 + AAC. */
        val CONSERVATIVE = DeviceCapabilities(
            videoDecoders = listOf(
                VideoDecoderCapability(
                    mimeType = "video/avc",
                    maxWidth = 1920,
                    maxHeight = 1080,
                    maxFrameRate = 60,
                ),
            ),
            audioCodecs = setOf("aac", "mp3"),
            maxAudioChannels = 2,
        )
    }
}

/** MIME types used across the capability report and source selection. */
object VideoMimeTypes {
    const val H264 = "video/avc"
    const val HEVC = "video/hevc"
    const val VP9 = "video/x-vnd.on2.vp9"
    const val AV1 = "video/av01"
    const val MPEG4 = "video/mp4v-es"
    const val DOLBY_VISION = "video/dolby-vision"

    fun fromCodecName(codec: String?): String? = when (codec?.lowercase()) {
        null -> null
        "h264", "avc", "avc1", "x264" -> H264
        "hevc", "h265", "hvc1", "hev1", "x265" -> HEVC
        "vp9" -> VP9
        "av1", "av01" -> AV1
        "mpeg4", "divx", "xvid" -> MPEG4
        "dvhe", "dvh1", "dolbyvision" -> DOLBY_VISION
        else -> null
    }
}
