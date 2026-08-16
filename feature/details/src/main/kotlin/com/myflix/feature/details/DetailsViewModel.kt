package com.myflix.feature.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myflix.core.common.error.DataError
import com.myflix.core.common.log.MyflixLog
import com.myflix.core.data.repository.MediaRepository
import com.myflix.core.data.repository.MyListRepository
import com.myflix.core.data.repository.ProgressRepository
import com.myflix.core.model.Episode
import com.myflix.core.model.MediaItem
import com.myflix.core.model.NextEpisodeSelector
import com.myflix.core.model.PlayAction
import com.myflix.core.model.PlaybackProgress
import com.myflix.core.model.Season
import com.myflix.core.model.ShowDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "Details"

sealed interface DetailsUiState {
    data object Loading : DetailsUiState

    data class MovieContent(
        val item: MediaItem,
        val progress: PlaybackProgress?,
        val inMyList: Boolean,
        val primaryAction: PlayAction,
    ) : DetailsUiState

    data class ShowContent(
        val details: ShowDetails,
        val inMyList: Boolean,
        val primaryAction: PlayAction,
        val selectedSeasonId: String?,
        val progressByEpisode: Map<String, PlaybackProgress>,
        val episodesLoading: Boolean,
    ) : DetailsUiState {
        val selectedSeason: Season?
            get() = details.seasons.firstOrNull { it.id == selectedSeasonId } ?: details.seasons.firstOrNull()

        val episodes: List<Episode>
            get() = selectedSeason?.let { details.episodesBySeason[it.id].orEmpty() }.orEmpty()
    }

    data class Error(val message: String, val itemMissing: Boolean) : DetailsUiState
}

@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val progressRepository: ProgressRepository,
    private val myListRepository: MyListRepository,
) : ViewModel() {

    private val itemId: String = requireNotNull(savedStateHandle.get<String>(ARG_ITEM_ID)) {
        "Details opened without an item id"
    }

    private val selectedSeasonId = MutableStateFlow<String?>(
        savedStateHandle.get<String>(KEY_SELECTED_SEASON),
    )
    private val error = MutableStateFlow<DataError?>(null)
    private val episodesLoading = MutableStateFlow(false)

    val uiState: StateFlow<DetailsUiState> = combine(
        mediaRepository.observeItem(itemId),
        mediaRepository.observeShowDetails(itemId),
        progressRepository.observeAllProgress(),
        myListRepository.itemIds,
        combine(selectedSeasonId, error, episodesLoading) { season, err, loading ->
            Triple(season, err, loading)
        },
    ) { item, showDetails, progress, myList, (season, err, loading) ->
        when {
            item == null && err != null -> DetailsUiState.Error(
                message = errorMessage(err),
                itemMissing = err is DataError.NotFound,
            )

            item == null -> DetailsUiState.Loading

            item.isShow && showDetails != null -> DetailsUiState.ShowContent(
                details = showDetails,
                inMyList = item.id in myList,
                primaryAction = NextEpisodeSelector.primaryAction(showDetails, progress),
                selectedSeasonId = season ?: showDetails.seasons.firstOrNull()?.id,
                progressByEpisode = progress,
                episodesLoading = loading,
            )

            item.isShow -> DetailsUiState.Loading

            else -> DetailsUiState.MovieContent(
                item = item,
                progress = progress[item.id],
                inMyList = item.id in myList,
                primaryAction = NextEpisodeSelector.movieAction(item, progress[item.id]),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailsUiState.Loading)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            episodesLoading.value = true
            try {
                mediaRepository.refreshDetails(itemId)
                error.value = null
            } catch (failure: DataError) {
                MyflixLog.w(TAG, "Details refresh failed for $itemId", failure)
                error.value = failure
                if (failure is DataError.NotFound) mediaRepository.forgetItem(itemId)
            } finally {
                episodesLoading.value = false
            }
        }
    }

    fun selectSeason(season: Season) {
        selectedSeasonId.value = season.id
    }

    fun toggleMyList() {
        viewModelScope.launch { myListRepository.toggle(itemId) }
    }

    fun markWatched(watched: Boolean, runtimeMs: Long?) {
        viewModelScope.launch { progressRepository.markWatched(itemId, runtimeMs, watched) }
    }

    fun markEpisodeWatched(episode: Episode, watched: Boolean) {
        viewModelScope.launch {
            progressRepository.markWatched(episode.id, episode.runtimeMs, watched)
        }
    }

    private fun errorMessage(error: DataError): String = when (error) {
        is DataError.NotFound -> "This item is no longer on the server."
        is DataError.Unauthorized -> "The server rejected this device — check Settings."
        else -> "Can't reach the media server."
    }

    companion object {
        const val ARG_ITEM_ID = "itemId"
        private const val KEY_SELECTED_SEASON = "selectedSeason"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
