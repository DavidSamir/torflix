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
)

