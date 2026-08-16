package com.myflix.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myflix.core.data.settings.SettingsRepository
import com.myflix.core.player.AspectMode
import com.myflix.core.player.PlaybackController
import com.myflix.core.player.PlaybackRequest
import com.myflix.core.player.PlayerUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin wrapper over [PlaybackController].
 *
 * The controller is a singleton because the same player is driven by the MediaSession; the ViewModel
 * exists to scope *screen* concerns — the overlay auto-hide timer and the debounced seek target.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val controller: PlaybackController,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val playableId: String = requireNotNull(savedStateHandle.get<String>(ARG_PLAYABLE_ID))
    private val showId: String? = savedStateHandle.get<String>(ARG_SHOW_ID)
    private val startPositionMs: Long? = savedStateHandle.get<Long>(ARG_START_POSITION)?.takeIf { it >= 0 }

    val state: StateFlow<PlayerUiState> = controller.state

    private val _controlsVisible = MutableStateFlow(true)
    val controlsVisible: StateFlow<Boolean> = _controlsVisible.asStateFlow()

    /** Non-null while the user is scrubbing: the timeline shows this, the player has not moved yet. */
    private val _pendingSeekMs = MutableStateFlow<Long?>(null)
    val pendingSeekMs: StateFlow<Long?> = _pendingSeekMs.asStateFlow()

    val player get() = controller.player
    val currentFrameRate: Float? get() = controller.currentFrameRate

    init {
        viewModelScope.launch {
            controller.open(
                PlaybackRequest(
                    playableId = playableId,
                    showId = showId,
                    startPositionMs = startPositionMs,
                ),
            )
            if (settingsRepository.settings.first().skipIntroAutomatically) {
                // Handled in the screen: it watches showSkipIntro and skips once.
            }
        }
    }

    fun showControls() {
        _controlsVisible.value = true
    }

    fun hideControls() {
        _controlsVisible.value = false
    }

    fun toggleControls() {
        _controlsVisible.value = !_controlsVisible.value
    }

    fun togglePlayPause() {
        controller.togglePlayPause()
        showControls()
    }

    /**
     * Accumulates a seek target instead of seeking per key press.
     *
     * Holding ← on the remote fires ~20 key events per second; seeking on each would thrash the
     * decoder. The screen commits the accumulated target after a short idle period (plan.md §7.4).
     */
    fun nudgeSeek(deltaMs: Long) {
        val base = _pendingSeekMs.value ?: state.value.positionMs
        val duration = state.value.durationMs
        val target = (base + deltaMs).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        _pendingSeekMs.value = target
        showControls()
    }

    fun commitSeek() {
        val target = _pendingSeekMs.value ?: return
        _pendingSeekMs.value = null
        controller.seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        _pendingSeekMs.value = null
        controller.seekTo(positionMs)
    }

    fun skipIntro() = controller.skipIntro()
    fun playNextEpisode() = controller.playNextEpisode(automatic = false)
    fun playPreviousEpisode() = controller.playPreviousEpisode()
    fun cancelNextEpisodeCountdown() = controller.cancelNextEpisodeCountdown()
    fun selectAudioTrack(id: String) = controller.selectAudioTrack(id)
    fun selectSubtitleTrack(id: String?) = controller.selectSubtitleTrack(id)
    fun setSpeed(speed: Float) = controller.setSpeed(speed)
    fun setAspectMode(mode: AspectMode) = controller.setAspectMode(mode)
    fun retry() = controller.retry()
    fun noteUserInput() = controller.noteUserInput()
    fun dismissStillWatching(continueWatching: Boolean) = controller.dismissStillWatching(continueWatching)

    /** Called when the player screen is left: saves progress and frees the decoder. */
    fun leavePlayer() = controller.stop(release = true)

    /** Called when the app goes to the background: TV video apps pause rather than play blind. */
    fun onBackground() = controller.pause()

    override fun onCleared() {
        controller.stop(release = true)
        super.onCleared()
    }

    companion object {
        const val ARG_PLAYABLE_ID = "playableId"
        const val ARG_SHOW_ID = "showId"
        const val ARG_START_POSITION = "startPositionMs"
    }
}
