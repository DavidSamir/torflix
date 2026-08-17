package com.torfilx.core.model

/** User-visible settings. The app has no server, so nothing here describes one. */
data class AppSettings(
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val subtitlesEnabledByDefault: Boolean = false,
    val autoplayNextEpisode: Boolean = true,
    val quality: QualityPreference = QualityPreference.AUTO,
    val frameRateMatching: Boolean = true,
    val tunneledPlayback: Boolean = true,
    val skipIntroAutomatically: Boolean = false,
    // --- Streaming engine ------------------------------------------------------------------------
    // Defaults reproduce the built-in behaviour; every one exists so a device or network that the
    // defaults do not suit can be made to work without a new build.
    /** Find peers over the distributed hash table. Some networks block it; trackers still work. */
    val useDht: Boolean = true,
    /** Add well-known public trackers on top of a magnet's own, so discovery never rests on one path. */
    val useExtraTrackers: Boolean = true,
    /** How long to wait for a swarm to deliver a title's details before giving up. */
    val metadataTimeout: MetadataTimeout = MetadataTimeout.STANDARD,
    /** How playback is fed from the download: stream while downloading, or buffer more first. */
    val streamingMode: StreamingMode = StreamingMode.SEQUENTIAL,
    // --- Player ----------------------------------------------------------------------------------
    /** Force software video decoding for devices whose hardware decoder rejects a stream. */
    val forceSoftwareDecoder: Boolean = false,
)

/** Presets for how long to wait for peers to send a title's metadata. */
enum class MetadataTimeout(val seconds: Int, val label: String) {
    QUICK(60, "1 min"),
    STANDARD(120, "2 min"),
    PATIENT(300, "5 min"),
}

/** How the player is fed from an in-progress torrent. */
enum class StreamingMode(val label: String) {
    /** Prioritise pieces in play order and start as soon as the front of the file is ready. */
    SEQUENTIAL("Stream (start fast)"),

    /** Buffer a larger head of the file before starting; steadier on weak or bursty connections. */
    BUFFERED("Buffer more first"),
}

