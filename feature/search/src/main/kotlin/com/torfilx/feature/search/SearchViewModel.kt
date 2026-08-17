package com.torfilx.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torfilx.core.common.error.DataError
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.model.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "Search"

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val isSearching: Boolean = false,
    val errorMessage: String? = null,
) {
    val showRecent: Boolean get() = query.length < MIN_QUERY_LENGTH && results.isEmpty()
    val showNoResults: Boolean
        get() = query.length >= MIN_QUERY_LENGTH && !isSearching && results.isEmpty() && errorMessage == null

    companion object {
        const val MIN_QUERY_LENGTH = 2
    }
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val results = MutableStateFlow<List<SearchResult>>(emptyList())
    private val searching = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SearchUiState> = combine(
        query,
        results,
        mediaRepository.recentSearches(),
        searching,
        errorMessage,
    ) { currentQuery, currentResults, recent, isSearching, error ->
        SearchUiState(
            query = currentQuery,
            results = currentResults,
            recentSearches = recent,
            isSearching = isSearching,
            errorMessage = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchUiState())

    init {
        observeQuery()
    }

    /**
     * Debounced so typing "blade" with a D-pad keyboard issues one request, not five; in-flight
     * requests are superseded by `collectLatest` semantics of the debounce + relaunch (plan.md §6.5).
     */
    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        query
            .debounce(DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { text ->
                if (text.length < SearchUiState.MIN_QUERY_LENGTH) {
                    results.value = emptyList()
                    errorMessage.value = null
                    searching.value = false
                    return@onEach
                }
                searching.value = true
                try {
                    results.value = mediaRepository.search(text)
                    errorMessage.value = null
                    if (results.value.isNotEmpty()) mediaRepository.recordSearch(text)
                } catch (error: DataError) {
                    TorfilxLog.w(TAG, "Search failed", error)
                    results.value = emptyList()
                    errorMessage.value = "Search ran into a problem. Please try again."
                } finally {
                    searching.value = false
                }
            }
            .launchIn(viewModelScope)
    }

    fun appendCharacter(character: Char) {
        query.value = (query.value + character).take(MAX_QUERY_LENGTH)
    }

    fun backspace() {
        query.value = query.value.dropLast(1)
    }

    fun clear() {
        query.value = ""
    }

    fun setQuery(text: String) {
        query.value = text.take(MAX_QUERY_LENGTH)
    }

    fun clearHistory() {
        viewModelScope.launch { mediaRepository.clearSearchHistory() }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val MAX_QUERY_LENGTH = 60
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
