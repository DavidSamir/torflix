package com.torfilx.core.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.common.error.DataError
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.data.torrent.TorrentCoordinator
import com.torfilx.core.torrent.TorrentError
import com.torfilx.core.model.AppSettings
import com.torfilx.core.model.Episode
import com.torfilx.core.model.MediaItem as DomainMediaItem
import com.torfilx.core.model.NextEpisodeSelector
import com.torfilx.core.model.ResumeRules
import com.torfilx.core.model.ShowDetails
import com.torfilx.core.model.SourceKind
import com.torfilx.core.model.SourceSelector
import com.torfilx.core.model.SubtitleFormat
import com.torfilx.core.model.SubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton
import androidx.media3.common.MediaItem as ExoMediaItem

private const val TAG = "Playback"

/**
 * Owns the player and all playback business logic.
 *
 * It is a singleton rather than per-screen state because the same player instance is also driven by
 * the `MediaSession` (remote transport keys and Alexa), and because playback must survive the
 * player screen being recomposed or briefly detached (plan.md §7.1, §7.6).
 */
@Singleton
class PlaybackController @Inject constructor(
    private val playerFactory: PlayerFactory,
    private val playbackInfoRepository: PlaybackInfoRepository,
    private val mediaRepository: MediaRepository,
    private val progressRepository: ProgressRepository,
    private val settingsRepository: SettingsRepository,
    private val torrentCoordinator: TorrentCoordinator,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    @Volatile
    var player: ExoPlayer? = null
        private set

    private var resolved: ResolvedPlayback? = null
    private var showDetails: ShowDetails? = null
    private var settings: AppSettings = AppSettings()
    private var positionJob: Job? = null
    private var countdownJob: Job? = null
    private var consecutiveAutoplays: Int = 0
    private var lastUserInputMs: Long = 0L
    private var lastSavedPositionMs: Long = -1L
    private var pausedForAudioFocus: Boolean = false

    /** Info hash of the torrent currently feeding the player, if any. */
    private var activeTorrentInfoHash: String? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val exo = player ?: return
            _state.value = _state.value.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                isLoading = playbackState == Player.STATE_IDLE && _state.value.error == null,
                durationMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: _state.value.durationMs,
            )
            if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
            if (!isPlaying) saveProgress(force = true)
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshTrackOptions()
        }
    }

    /** Creates the player if needed. Must be called from the main thread. */
    fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val created = playerFactory.create(settings)
        created.addListener(listener)
        player = created
        return created
    }

    /**
     * Loads and starts an item.
     *
     * Everything that can fail — playback info, source selection, decoding — resolves into a typed
     * [PlaybackError] in the state rather than an exception, because a failure here must leave the
     * user on the player screen with a retry, not bounce them to Home (plan.md §7.4).
     */
    suspend fun open(request: PlaybackRequest) {
        settings = settingsRepository.settings.first()
        _state.value = PlayerUiState(isLoading = true)

        val info = try {
            playbackInfoRepository.playbackInfo(request.playableId)
        } catch (error: DataError) {
            _state.value = _state.value.copy(isLoading = false, error = error.toPlaybackError())
            return
        }

        // An explicit choice from the details screen wins over automatic selection — that is the
        // whole point of offering "server or swarm" to the user.
        val chosen = request.sourceId?.let { id -> info.sources.firstOrNull { it.id == id } }
        val selection = if (chosen != null) {
            SourceSelector.Result(chosen, SourceSelector.Reason.DIRECT_PLAY)
        } else {
            SourceSelector.select(
                sources = info.sources,
                capabilities = playbackInfoRepository.capabilities(),
                preference = settings.quality,
                failedSourceIds = playbackInfoRepository.failedSourceIds(request.playableId),
            )
        }
        val source = selection.source
        if (source == null) {
            TorfilxLog.w(TAG, "No playable source for ${request.playableId}: ${selection.reason}")
            _state.value = _state.value.copy(
                isLoading = false,
                error = PlaybackError.Unsupported(
                    when (selection.reason) {
                        SourceSelector.Reason.ALL_SOURCES_FAILED ->
                            "This file could not be played on this device."
                        SourceSelector.Reason.NO_SOURCES ->
                            "The server did not offer any playable version of this item."
                        else ->
                            "This file's video or audio format is not supported by this device."
                    },
                ),
            )
            return
        }

        val episode = mediaRepository.episode(request.playableId)
        val item = when {
            episode != null -> mediaRepository.item(episode.showId)
            else -> mediaRepository.item(request.playableId)
        }
        showDetails = (request.showId ?: episode?.showId)?.let { showId ->
            runCatching { mediaRepository.observeShowDetails(showId).first() }.getOrNull()
        }

        val storedProgress = progressRepository.get(request.playableId)
        val durationMs = info.durationMs
            ?: episode?.runtimeMs
            ?: item?.runtimeMs
            ?: storedProgress?.durationMs
            ?: 0L
        val startPosition = request.startPositionMs
            ?: ResumeRules.resumePositionMs(info.resume ?: storedProgress)

        resolved = ResolvedPlayback(
            playableId = request.playableId,
            showId = request.showId ?: episode?.showId,
            item = item,
            episode = episode,
            subtitles = info.subtitles,
            audio = info.audioTracks,
            markers = info.markers,
            spriteSheet = info.spriteSheet,
            durationMs = durationMs,
            sourceId = source.id,
            isTranscode = source.kind == SourceKind.HLS,
            frameRate = source.frameRate,
        )

        // A torrent source is a magnet, not a URL: the engine resolves it to a loopback HTTP stream
        // that serves pieces as they arrive, so playback starts long before the download finishes.
        val playbackUrl = if (source.kind == SourceKind.TORRENT) {
            val magnet = source.magnetUri ?: source.url
            try {
                val stream = torrentCoordinator.stream(magnet)
                activeTorrentInfoHash = stream.infoHash
                stream.url
            } catch (error: TorrentError) {
                TorfilxLog.w(TAG, "Torrent stream failed for ${request.playableId}", error)
                _state.value = _state.value.copy(isLoading = false, error = error.toPlaybackError())
                return
            }
        } else {
            activeTorrentInfoHash = null
            source.url
        }

        val exoItem = buildMediaItem(playbackUrl, info.subtitles, source.kind)
        withContext(Dispatchers.Main) {
            val exo = ensurePlayer()
            exo.setMediaItem(exoItem, startPosition)
            exo.prepare()
            exo.playWhenReady = true
        }

        _state.value = _state.value.copy(
            isLoading = false,
            error = null,
            title = item?.title.orEmpty(),
            subtitle = episode?.let { ep ->
                buildString {
                    append("S${ep.seasonNumber}:E${ep.episodeNumber}")
                    ep.title?.let { append(" · $it") }
                }
            },
            item = item,
            episode = episode,
            durationMs = durationMs,
            positionMs = startPosition,
            markers = info.markers,
            spriteSheet = info.spriteSheet,
            isTranscoding = source.kind == SourceKind.HLS,
            nextEpisode = nextEpisode(),
            subtitlesEnabled = settings.subtitlesEnabledByDefault,
        )
        lastUserInputMs = System.currentTimeMillis()
        startPositionTicker()
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaItem(
        url: String,
        subtitles: List<SubtitleTrack>,
        kind: SourceKind,
    ): ExoMediaItem {
        val sidecars = subtitles
            // Only text sidecars can be attached; bitmap subtitles must come from the container.
            .filter { !it.isEmbedded && it.url != null && !it.isBitmap }
            .map { track ->
                ExoMediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(track.url))
                    .setMimeType(track.format.toMimeType())
                    .setLanguage(track.language)
                    .setLabel(track.label)
                    .setId(track.id)
                    .setSelectionFlags(if (track.isForced) C.SELECTION_FLAG_FORCED else 0)
                    .build()
            }

        return ExoMediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(sidecars)
            .apply {
                if (kind == SourceKind.HLS) setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            .build()
    }

    // --- Transport ------------------------------------------------------------------------------

    fun togglePlayPause() {
        noteUserInput()
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun play() {
        noteUserInput()
        player?.play()
    }

    fun pause() {
        noteUserInput()
        player?.pause()
        saveProgress(force = true)
    }

    fun seekTo(positionMs: Long) {
        noteUserInput()
        val exo = player ?: return
        val clamped = positionMs.coerceIn(0L, exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        exo.seekTo(clamped)
        _state.value = _state.value.copy(positionMs = clamped)
        saveProgress(force = true)
    }

    fun seekBy(deltaMs: Long) = seekTo((player?.currentPosition ?: 0L) + deltaMs)

    fun setSpeed(speed: Float) {
        noteUserInput()
        player?.setPlaybackSpeed(speed)
        _state.value = _state.value.copy(playbackSpeed = speed)
    }

    fun setAspectMode(mode: AspectMode) {
        noteUserInput()
        _state.value = _state.value.copy(aspectMode = mode)
    }

    fun noteUserInput() {
        lastUserInputMs = System.currentTimeMillis()
        if (_state.value.showStillWatching) {
            _state.value = _state.value.copy(showStillWatching = false)
            consecutiveAutoplays = 0
            player?.play()
        }
    }

    // --- Tracks ---------------------------------------------------------------------------------

    @OptIn(UnstableApi::class)
    fun selectAudioTrack(optionId: String) {
        applyTrackSelection(C.TRACK_TYPE_AUDIO, optionId)
    }

    @OptIn(UnstableApi::class)
    fun selectSubtitleTrack(optionId: String?) {
        if (optionId == null) {
            player?.let { exo ->
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            _state.value = _state.value.copy(subtitlesEnabled = false)
            refreshTrackOptions()
            return
        }
        player?.let { exo ->
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }
        applyTrackSelection(C.TRACK_TYPE_TEXT, optionId)
        _state.value = _state.value.copy(subtitlesEnabled = true)
    }

    @OptIn(UnstableApi::class)
    private fun applyTrackSelection(trackType: Int, optionId: String) {
        val exo = player ?: return
        val groups = exo.currentTracks.groups.filter { it.type == trackType }
        val target = groups.firstOrNull { group ->
            (0 until group.length).any { index -> group.getTrackFormat(index).id == optionId } ||
                group.mediaTrackGroup.id == optionId
        } ?: return
        val trackIndex = (0 until target.length).firstOrNull { index ->
            target.getTrackFormat(index).id == optionId
        } ?: 0

        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, trackIndex))
            .build()
        refreshTrackOptions()
    }

    @OptIn(UnstableApi::class)
    private fun refreshTrackOptions() {
        val exo = player ?: return
        val resolvedInfo = resolved

        val audio = mutableListOf<TrackOption>()
        val text = mutableListOf<TrackOption>()

        exo.currentTracks.groups.forEach { group ->
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val id = format.id ?: "${group.type}-$index"
                val selected = group.isTrackSelected(index)
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio += TrackOption(
                        id = id,
                        label = audioLabel(format.language, format.label, format.channelCount, format.codecs),
                        language = format.language,
                        isSelected = selected,
                        isSelectable = group.isTrackSupported(index),
                        unavailableReason = if (group.isTrackSupported(index)) {
                            null
                        } else {
                            "Not supported by this device"
                        },
                    )

                    C.TRACK_TYPE_TEXT -> text += TrackOption(
                        id = id,
                        label = format.label ?: format.language ?: "Subtitle",
                        language = format.language,
                        isSelected = selected,
                        isSelectable = group.isTrackSupported(index),
                        unavailableReason = if (group.isTrackSupported(index)) {
                            null
                        } else {
                            "Image subtitles cannot be shown for this stream"
                        },
                    )

                    else -> Unit
                }
            }
        }

        // Bitmap subtitle tracks the server declared but that ExoPlayer cannot render in this
        // container are surfaced as disabled entries, with the reason, rather than hidden.
        resolvedInfo?.subtitles?.filter { it.isBitmap }?.forEach { track ->
            if (text.none { it.id == track.id }) {
                text += TrackOption(
                    id = track.id,
                    label = track.label ?: track.language ?: "Image subtitles",
                    language = track.language,
                    isSelected = false,
                    isSelectable = false,
                    unavailableReason = "Image-based subtitles are not supported in this stream",
                )
            }
        }

        _state.value = _state.value.copy(audioTracks = audio, subtitleTracks = text)
    }

    private fun audioLabel(language: String?, label: String?, channels: Int, codecs: String?): String {
        val parts = listOfNotNull(
            label ?: language,
            when {
                channels >= 6 -> "5.1"
                channels == 2 -> "Stereo"
                channels == 1 -> "Mono"
                else -> null
            },
            codecs?.substringBefore('.')?.uppercase(),
        )
        return parts.joinToString(" · ").ifEmpty { "Audio" }
    }

    // --- Progress -------------------------------------------------------------------------------

    private fun startPositionTicker() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (true) {
                withContext(Dispatchers.Main) { tick() }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun tick() {
        val exo = player ?: return
        val position = exo.currentPosition
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: _state.value.durationMs
        val markers = _state.value.markers

        val showSkip = markers.intro?.contains(position) == true
        val creditsStart = markers.creditsStartMs
        val nextEpisode = _state.value.nextEpisode
        val shouldCountdown = creditsStart != null &&
            position >= creditsStart &&
            nextEpisode != null &&
            exo.isPlaying

        _state.value = _state.value.copy(
            positionMs = position,
            bufferedPositionMs = exo.bufferedPosition,
            durationMs = duration,
            showSkipIntro = showSkip,
        )

        if (shouldCountdown && countdownJob == null) startNextEpisodeCountdown()

        if (position - lastSavedPositionMs >= SAVE_INTERVAL_MS || lastSavedPositionMs < 0) {
            saveProgress(force = false)
        }

        maybeShowStillWatching()
    }

    private fun saveProgress(force: Boolean) {
        val exo = player ?: return
        val info = resolved ?: return
        val position = exo.currentPosition
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: info.durationMs
        if (duration <= 0) return
        if (!force && position == lastSavedPositionMs) return
        lastSavedPositionMs = position

        scope.launch {
            progressRepository.save(
                itemId = info.playableId,
                positionMs = position,
                durationMs = duration,
                showId = info.showId,
            )
        }
    }

    /** Saves and releases. Called when the player screen is left or the process is going away. */
    fun stop(release: Boolean = true) {
        saveProgress(force = true)
        activeTorrentInfoHash?.let { infoHash ->
            activeTorrentInfoHash = null
            // Leaving the player stops the stream; whether it keeps seeding is the user.s setting.
            scope.launch { runCatching { torrentCoordinator.stopStreaming(infoHash) } }
        }
        positionJob?.cancel()
        positionJob = null
        countdownJob?.cancel()
        countdownJob = null
        val exo = player
        if (release && exo != null) {
            exo.removeListener(listener)
            exo.release()
            player = null
        } else {
            exo?.pause()
        }
        _state.value = PlayerUiState(isLoading = false)
        resolved = null
        showDetails = null
        consecutiveAutoplays = 0
        lastSavedPositionMs = -1L
    }

    // --- Episodes -------------------------------------------------------------------------------

    private fun nextEpisode(): Episode? {
        val details = showDetails ?: return null
        val currentId = resolved?.playableId ?: return null
        return NextEpisodeSelector.nextEpisodeAfter(details, currentId)
    }

    private fun startNextEpisodeCountdown() {
        if (!settings.autoplayNextEpisode) {
            _state.value = _state.value.copy(nextEpisodeCountdownSeconds = null)
            return
        }
        countdownJob = scope.launch {
            for (second in NEXT_EPISODE_COUNTDOWN_SECONDS downTo 1) {
                _state.value = _state.value.copy(nextEpisodeCountdownSeconds = second)
                delay(1_000)
            }
            _state.value = _state.value.copy(nextEpisodeCountdownSeconds = null)
            playNextEpisode(automatic = true)
        }
    }

    fun cancelNextEpisodeCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _state.value = _state.value.copy(nextEpisodeCountdownSeconds = null)
    }

    fun playNextEpisode(automatic: Boolean = false) {
        val next = _state.value.nextEpisode ?: return
        cancelNextEpisodeCountdown()
        consecutiveAutoplays = if (automatic) consecutiveAutoplays + 1 else 0
        scope.launch {
            open(PlaybackRequest(playableId = next.id, showId = next.showId, startPositionMs = 0L))
        }
    }

    fun playPreviousEpisode() {
        val details = showDetails ?: return
        val currentId = resolved?.playableId ?: return
        val previous = NextEpisodeSelector.previousEpisodeBefore(details, currentId) ?: return
        scope.launch {
            open(PlaybackRequest(playableId = previous.id, showId = previous.showId))
        }
    }

    fun skipIntro() {
        val end = _state.value.markers.intro?.endMs ?: return
        seekTo(end)
    }

    private fun onPlaybackEnded() {
        saveProgress(force = true)
        val info = resolved ?: return
        scope.launch {
            // Mark fully watched so the item leaves Continue Watching immediately.
            progressRepository.markWatched(info.playableId, info.durationMs, watched = true)
        }
        if (_state.value.nextEpisode != null && settings.autoplayNextEpisode) {
            playNextEpisode(automatic = true)
        }
    }

    /**
     * "Are you still watching?" — shown after several unattended autoplays or a long unattended
     * session, and it pauses playback rather than merely asking (plan.md §7.4).
     */
    private fun maybeShowStillWatching() {
        if (_state.value.showStillWatching) return
        val idleMs = System.currentTimeMillis() - lastUserInputMs
        val trigger = consecutiveAutoplays >= STILL_WATCHING_EPISODES ||
            idleMs >= STILL_WATCHING_IDLE_MS
        if (trigger && _state.value.isPlaying) {
            player?.pause()
            _state.value = _state.value.copy(showStillWatching = true)
        }
    }

    fun dismissStillWatching(continueWatching: Boolean) {
        _state.value = _state.value.copy(showStillWatching = false)
        consecutiveAutoplays = 0
        lastUserInputMs = System.currentTimeMillis()
        if (continueWatching) player?.play() else stop(release = false)
    }

    // --- Errors ---------------------------------------------------------------------------------

    /**
     * Decoder and container failures trigger exactly one retry on a different source before the user
     * is told anything, because "server said direct play, device disagreed" is a normal outcome for
     * a mixed personal library (plan.md §7.2).
     */
    private fun handlePlayerError(error: PlaybackException) {
        val info = resolved
        TorfilxLog.e(TAG, "Player error ${error.errorCodeName} for ${info?.playableId}", error)

        val isDecoderProblem = when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            -> true

            else -> false
        }

        if (isDecoderProblem && info != null) {
            playbackInfoRepository.markSourceFailed(info.playableId, info.sourceId)
            scope.launch {
                open(
                    PlaybackRequest(
                        playableId = info.playableId,
                        showId = info.showId,
                        startPositionMs = _state.value.positionMs,
                    ),
                )
            }
            return
        }

        _state.value = _state.value.copy(
            isLoading = false,
            error = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                -> PlaybackError.Network("Lost connection to the media server.")

                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    PlaybackError.NotFound("This file is no longer available on the server.")

                PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ->
                    PlaybackError.Unsupported("This file is encrypted and cannot be played.")

                else -> PlaybackError.Unknown("Playback failed (${error.errorCodeName}).")
            },
        )
    }

    /** Called by the UI's retry button. */
    fun retry() {
        val info = resolved ?: return
        _state.value = _state.value.copy(error = null, isLoading = true)
        scope.launch {
            open(
                PlaybackRequest(
                    playableId = info.playableId,
                    showId = info.showId,
                    startPositionMs = _state.value.positionMs,
                ),
            )
        }
    }

    fun onAudioFocusLostTransiently() {
        if (_state.value.isPlaying) {
            pausedForAudioFocus = true
            player?.pause()
        }
    }

    fun onAudioFocusRegained() {
        if (pausedForAudioFocus) {
            pausedForAudioFocus = false
            player?.play()
        }
    }

    val currentFrameRate: Float? get() = resolved?.frameRate

    private fun DataError.toPlaybackError(): PlaybackError = when (this) {
        is DataError.Unauthorized -> PlaybackError.Unauthorized("The server rejected this device's token.")
        is DataError.NotFound -> PlaybackError.NotFound("This item is no longer on the server.")
        is DataError.ServerUnreachable, is DataError.Timeout ->
            PlaybackError.Network("Can't reach the media server.")
        is DataError.NotConfigured -> PlaybackError.Network("No media server is configured.", retryable = false)
        else -> PlaybackError.Unknown(message ?: "Playback could not be started.")
    }

    private fun TorrentError.toPlaybackError(): PlaybackError = when (this) {
        is TorrentError.NotConsented -> PlaybackError.SharingNotEnabled(
            "Turn on sharing to stream this title over BitTorrent.",
        )

        is TorrentError.NoSpace -> PlaybackError.OutOfSpace(message ?: "Not enough free space.")
        is TorrentError.InvalidMagnet -> PlaybackError.Unsupported(
            "This title.s magnet link is not valid, so it cannot be streamed.",
        )

        is TorrentError.MetadataTimeout -> PlaybackError.Network(
            "No peers are sharing this title right now.",
        )

        is TorrentError.NoPlayableFile -> PlaybackError.Unsupported(
            "That torrent contains no video file.",
        )

        is TorrentError.EngineUnavailable -> PlaybackError.Unsupported(
            "BitTorrent is not supported on this device.",
        )
    }

    private fun SubtitleFormat.toMimeType(): String = when (this) {
        SubtitleFormat.VTT -> MimeTypes.TEXT_VTT
        SubtitleFormat.SRT -> MimeTypes.APPLICATION_SUBRIP
        SubtitleFormat.ASS -> MimeTypes.TEXT_SSA
        SubtitleFormat.PGS -> MimeTypes.APPLICATION_PGS
        SubtitleFormat.DVD_SUB -> MimeTypes.APPLICATION_VOBSUB
        SubtitleFormat.UNKNOWN -> MimeTypes.TEXT_VTT
    }

    private companion object {
        const val TICK_INTERVAL_MS = 500L
        const val SAVE_INTERVAL_MS = 10_000L
        const val NEXT_EPISODE_COUNTDOWN_SECONDS = 10
        const val STILL_WATCHING_EPISODES = 3
        const val STILL_WATCHING_IDLE_MS = 3 * 60 * 60 * 1000L
    }
}
