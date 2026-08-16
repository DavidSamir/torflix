package com.myflix.core.player

import com.myflix.core.model.AudioTrackInfo
import com.myflix.core.model.Episode
import com.myflix.core.model.Markers
import com.myflix.core.model.MediaItem
import com.myflix.core.model.SpriteSheet
import com.myflix.core.model.SubtitleTrack

/** What the UI asks the player to open. */
data class PlaybackRequest(
    /** Movie id, or episode id when playing a show. */
    val playableId: String,
    val showId: String? = null,
    /** Overrides the stored resume position when non-null (e.g. "Play from beginning"). */
    val startPositionMs: Long? = null,
)

/** A selectable track in the audio/subtitle picker. */
data class TrackOption(
    val id: String,
    val label: String,
    val language: String?,
    val isSelected: Boolean,
    val isSelectable: Boolean = true,
    /** Why a track cannot be selected (e.g. bitmap subtitles in an HLS stream). */
    val unavailableReason: String? = null,
)

enum class AspectMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    ZOOM("Zoom"),
}

/** Distinct failure kinds so each gets its own copy and recovery action (plan.md §7.4). */
sealed interface PlaybackError {
    val message: String

    data class Network(override val message: String, val retryable: Boolean = true) : PlaybackError
    data class Unsupported(override val message: String) : PlaybackError
    data class NotFound(override val message: String) : PlaybackError
    data class Unauthorized(override val message: String) : PlaybackError
    data class Unknown(override val message: String) : PlaybackError
}

/** Everything the player screen renders. */
data class PlayerUiState(
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val title: String = "",
    val subtitle: String? = null,
    val item: MediaItem? = null,
    val episode: Episode? = null,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val subtitlesEnabled: Boolean = false,
    val markers: Markers = Markers(),
    val spriteSheet: SpriteSheet? = null,
    val aspectMode: AspectMode = AspectMode.FIT,
    val playbackSpeed: Float = 1f,
    val error: PlaybackError? = null,
    val showSkipIntro: Boolean = false,
    val nextEpisode: Episode? = null,
    val nextEpisodeCountdownSeconds: Int? = null,
    val showStillWatching: Boolean = false,
    val isTranscoding: Boolean = false,
    /** Set while a display mode switch settles, so the UI can stay black instead of flashing. */
    val isSwitchingDisplayMode: Boolean = false,
) {
    val canSeek: Boolean get() = durationMs > 0 && error == null
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

/** Source data resolved for the current item, kept for retries and track mapping. */
internal data class ResolvedPlayback(
    val playableId: String,
    val showId: String?,
    val item: MediaItem?,
    val episode: Episode?,
    val subtitles: List<SubtitleTrack>,
    val audio: List<AudioTrackInfo>,
    val markers: Markers,
    val spriteSheet: SpriteSheet?,
    val durationMs: Long,
    val sourceId: String,
    val isTranscode: Boolean,
    val frameRate: Float?,
)
