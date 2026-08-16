package com.torfilx.feature.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torfilx.core.common.error.DataError
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.MyListRepository
import com.torfilx.core.data.catalog.BundledCatalog
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.data.torrent.TorrentCoordinator
import com.torfilx.core.model.MediaSource
import com.torfilx.core.model.Episode
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.NextEpisodeSelector
import com.torfilx.core.model.PlayAction
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.Season
import com.torfilx.core.model.ShowDetails
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
    private val catalog: BundledCatalog,
    private val settingsRepository: SettingsRepository,
    private val torrentCoordinator: TorrentCoordinator,
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

    /** The catalogue is local, so there is nothing to refresh — this only clears a stale error. */
    fun refresh() {
        error.value = null
    }

    fun selectSeason(season: Season) {
        selectedSeasonId.value = season.id
    }

    /**
     * Torrent sources for this title, read from the bundled catalogue.
     *
     * Local, so the details screen can offer "play from the swarm" without any network call — which
     * is exactly the case that matters when the media server is off.
     */
    /** The item this screen is showing; used when starting playback of a chosen source. */
    val itemIdForPlayback: String = itemId

    val torrentSources: List<MediaSource> = catalog.sourcesFor(itemId)

    val torrentAvailable: Boolean = torrentSources.isNotEmpty() && torrentCoordinator.isAvailable()

    val sharingConsent: StateFlow<Boolean> = settingsRepository.sharingConsent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private val _pendingTorrentSource = MutableStateFlow<MediaSource?>(null)

    /** Non-null while the consent dialog is asking about this source. */
    val pendingTorrentSource: StateFlow<MediaSource?> = _pendingTorrentSource

    /**
     * Returns the source to play immediately, or null when consent must be collected first.
     */
    fun requestTorrentPlayback(source: MediaSource): MediaSource? {
        if (sharingConsent.value) return source
        _pendingTorrentSource.value = source
        return null
    }

    fun onConsentAccepted(onReady: (MediaSource) -> Unit) {
        val pending = _pendingTorrentSource.value
        viewModelScope.launch {
            settingsRepository.setSharingConsent(true)
            _pendingTorrentSource.value = null
            pending?.let(onReady)
        }
    }

    fun onConsentDeclined() {
        viewModelScope.launch {
            settingsRepository.setSharingConsent(false)
            _pendingTorrentSource.value = null
        }
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
