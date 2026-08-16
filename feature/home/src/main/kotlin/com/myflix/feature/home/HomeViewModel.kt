package com.myflix.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myflix.core.common.error.DataError
import com.myflix.core.common.log.MyflixLog
import com.myflix.core.data.repository.MediaRepository
import com.myflix.core.data.repository.MyListRepository
import com.myflix.core.data.repository.ProgressRepository
import com.myflix.core.data.settings.SettingsRepository
import com.myflix.core.model.HeroItem
import com.myflix.core.model.HomeRow
import com.myflix.core.model.HomeRowKind
import com.myflix.core.model.MediaCard
import com.myflix.core.model.NextEpisodeSelector
import com.myflix.core.model.PlayAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "Home"

/** What Home renders. Loading/Content/Error are distinct so the UI never guesses (plan.md §3). */
sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Content(
        val hero: List<HeroItem>,
        val rows: List<HomeRow>,
        /** Shown as a non-blocking banner over cached content when a refresh failed. */
        val staleWarning: String? = null,
    ) : HomeUiState

    data class Error(
        val title: String,
        val message: String,
        val showSettingsAction: Boolean,
    ) : HomeUiState

    data class Empty(val serverConfigured: Boolean) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val progressRepository: ProgressRepository,
    private val myListRepository: MyListRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val refreshError = MutableStateFlow<DataError?>(null)
    private val isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        mediaRepository.observeHome(),
        mediaRepository.observeItemCount(),
        settingsRepository.settings,
        refreshError,
        isRefreshing,
    ) { rows, itemCount, settings, error, refreshing ->
        when {
            rows.isNotEmpty() -> HomeUiState.Content(
                hero = heroItems(rows),
                rows = rows,
                staleWarning = error?.let { staleMessage(it) },
            )

            refreshing -> HomeUiState.Loading

            error != null -> HomeUiState.Error(
                title = errorTitle(error),
                message = errorMessage(error),
                showSettingsAction = error is DataError.NotConfigured || error is DataError.Unauthorized,
            )

            itemCount == 0 && !settings.isServerConfigured -> HomeUiState.Empty(serverConfigured = false)
            itemCount == 0 -> HomeUiState.Empty(serverConfigured = true)
            else -> HomeUiState.Loading
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState.Loading,
    )

    init {
        refresh()
    }

    fun refresh() {
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                mediaRepository.refreshHome()
                mediaRepository.refreshLibrary()
                refreshError.value = null
            } catch (error: DataError) {
                MyflixLog.w(TAG, "Home refresh failed", error)
                refreshError.value = error
            } finally {
                isRefreshing.value = false
            }
        }
    }

    fun toggleMyList(card: MediaCard) {
        viewModelScope.launch { myListRepository.toggle(card.item.id) }
    }

    /** Menu → Remove on a Continue Watching card. */
    fun removeFromContinueWatching(card: MediaCard) {
        viewModelScope.launch { progressRepository.remove(card.playableId) }
    }

    fun markWatched(card: MediaCard, watched: Boolean) {
        viewModelScope.launch {
            progressRepository.markWatched(card.playableId, card.item.runtimeMs, watched)
        }
    }

    /**
     * Resolves what pressing Play on a card should do, so the caller can navigate straight into the
     * player for a movie or the right episode for a show.
     */
    suspend fun playActionFor(card: MediaCard): PlayAction {
        card.episode?.let { episode ->
            val progress = progressRepository.get(episode.id)
            return PlayAction.PlayEpisode(
                episode = episode,
                positionMs = com.myflix.core.model.ResumeRules.resumePositionMs(progress),
                kind = PlayAction.EpisodeActionKind.RESUME,
            )
        }
        if (card.item.isShow) {
            val details = mediaRepository.observeShowDetails(card.item.id).first()
                ?: return PlayAction.Unavailable
            val progressMap = progressRepository.progressForShow(card.item.id)
            if (details.episodesInAiredOrder.isEmpty()) {
                runCatching { mediaRepository.refreshDetails(card.item.id) }
                val refreshed = mediaRepository.observeShowDetails(card.item.id).first()
                    ?: return PlayAction.Unavailable
                return NextEpisodeSelector.primaryAction(refreshed, progressMap)
            }
            return NextEpisodeSelector.primaryAction(details, progressMap)
        }
        return NextEpisodeSelector.movieAction(card.item, progressRepository.get(card.item.id))
    }

    private fun heroItems(rows: List<HomeRow>): List<HeroItem> {
        // Prefer Continue Watching for the hero — the most likely thing the user wants — then fall
        // back to the newest additions.
        val source = rows.firstOrNull { it.kind == HomeRowKind.CONTINUE_WATCHING }
            ?: rows.firstOrNull { it.kind == HomeRowKind.RECENTLY_ADDED }
            ?: rows.firstOrNull()
        return source?.items.orEmpty().take(HERO_COUNT).map { card ->
            HeroItem(
                card = card,
                action = when {
                    card.episode != null -> PlayAction.PlayEpisode(
                        episode = card.episode!!,
                        positionMs = card.progress?.positionMs ?: 0L,
                        kind = PlayAction.EpisodeActionKind.RESUME,
                    )

                    card.item.isShow -> PlayAction.Unavailable
                    else -> NextEpisodeSelector.movieAction(card.item, card.progress)
                },
            )
        }
    }

    private fun staleMessage(error: DataError): String = when (error) {
        is DataError.Unauthorized -> "The server rejected this device — check the token in Settings."
        is DataError.NotConfigured -> "No media server configured."
        else -> "Can't reach the server — showing your saved library."
    }

    private fun errorTitle(error: DataError): String = when (error) {
        is DataError.NotConfigured -> "No media server yet"
        is DataError.Unauthorized -> "The server rejected this device"
        else -> "Can't reach your media server"
    }

    private fun errorMessage(error: DataError): String = when (error) {
        is DataError.NotConfigured -> "Add your server's address in Settings to see your library."
        is DataError.Unauthorized -> "Check the API token in Settings."
        is DataError.Timeout -> "The server took too long to answer. Is the PC awake?"
        else -> "Check that the PC is switched on and on the same network."
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val HERO_COUNT = 5
    }
}
