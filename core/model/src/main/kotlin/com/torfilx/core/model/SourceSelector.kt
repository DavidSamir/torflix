package com.torfilx.core.model

/** User preference for how playback should be delivered (Settings, plan.md §6.7). */
enum class QualityPreference {
    /** Direct play whenever the device can decode it, otherwise transcode. */
    AUTO,

    /** Never transcode; fail loudly instead (useful for diagnosing the server). */
    DIRECT_ONLY,

    /** Prefer a source at or below 1080p — for slow Wi-Fi or the 1.5 GB sticks. */
    CAP_1080P,
}

/**
 * Picks which [MediaSource] to hand to the player.
 *
 * Rules (plan.md §7.2):
 * - a source is only playable if the device reports a decoder for its codec at that resolution,
 *   its HDR type is displayable, and at least one of its audio codecs is supported;
 * - direct play is preferred over transcoding because it costs the server nothing;
 * - sources that already failed to play for this item are excluded, which is what makes the
 *   "direct play failed → retry once as HLS" fallback work;
 * - `CAP_1080P` demotes (but does not exclude) sources above 1080p, so playback still works when a
 *   4K file is the only thing on offer.
 */
object SourceSelector {

    data class Result(
        val source: MediaSource?,
        val reason: Reason,
    )

    enum class Reason {
        DIRECT_PLAY,
        TRANSCODE,
        NO_COMPATIBLE_SOURCE,
        ALL_SOURCES_FAILED,
        NO_SOURCES,
    }

    fun select(
        sources: List<MediaSource>,
        capabilities: DeviceCapabilities,
        preference: QualityPreference = QualityPreference.AUTO,
        failedSourceIds: Set<String> = emptySet(),
    ): Result {
        if (sources.isEmpty()) return Result(null, Reason.NO_SOURCES)

        // Torrent is the only delivery mechanism the app has, so it is selectable automatically.
        // Consent is enforced one layer down, in the engine: selecting a source never starts an
        // upload by itself.
        val remaining = sources.filterNot { it.id in failedSourceIds }
        if (remaining.isEmpty()) return Result(null, Reason.ALL_SOURCES_FAILED)

        val playable = remaining.filter { canPlay(it, capabilities) }
        val candidates = when (preference) {
            QualityPreference.DIRECT_ONLY -> playable.filter { it.kind == SourceKind.DIRECT }
            else -> playable
        }
        if (candidates.isEmpty()) return Result(null, Reason.NO_COMPATIBLE_SOURCE)

        val best = candidates.maxWithOrNull(comparator(preference)) ?: return Result(null, Reason.NO_COMPATIBLE_SOURCE)
        val reason = if (best.kind == SourceKind.DIRECT) Reason.DIRECT_PLAY else Reason.TRANSCODE
        return Result(best, reason)
    }

    /** True when this device can actually decode and display the source. */
    fun canPlay(source: MediaSource, capabilities: DeviceCapabilities): Boolean {
        // Dolby Vision profile 7 (dual-layer Blu-ray remux) is not decodable on any Fire TV.
        if (source.hdr == HdrType.DOLBY_VISION && source.dolbyVisionProfile == 7) return false

        // HLS is remuxed/transcoded by the server to a device-compatible stream; the codec listed on
        // the source is what the server promises to deliver, so it is still checked.
        val mime = VideoMimeTypes.fromCodecName(source.videoCodec)
        val videoOk = when {
            source.videoCodec == null -> true // server did not say; trust it and fall back on error
            mime == null -> false
            else -> capabilities.supportsVideo(mime, source.width, source.height)
        }
        if (!videoOk) return false

        if (!capabilities.supportsHdr(source.hdr)) return false

        val audioOk = source.audioCodecs.isEmpty() ||
            source.audioCodecs.any { capabilities.supportsAudio(it) } ||
            source.kind == SourceKind.HLS // the server transcodes audio for HLS
        return audioOk
    }

    private fun comparator(preference: QualityPreference): Comparator<MediaSource> =
        compareBy<MediaSource> { source ->
            if (preference == QualityPreference.CAP_1080P && (source.height ?: 0) > 1080) 0 else 1
        }
            .thenBy { if (it.kind == SourceKind.DIRECT) 1 else 0 }
            .thenBy { it.height ?: 0 }
            .thenBy { it.bitrate ?: 0L }
}
