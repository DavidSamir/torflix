package com.torfilx.core.data.repository

import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.common.time.TimeProvider
import com.torfilx.core.data.catalog.BundledCatalog
import com.torfilx.core.data.database.SearchHistoryDao
import com.torfilx.core.model.HomeRow
import com.torfilx.core.model.HomeRowKind
import com.torfilx.core.model.LibraryQuery
import com.torfilx.core.model.LibrarySort
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.SearchResult
import com.torfilx.core.model.WatchedFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library.
 *
 * One source of titles: the catalogue bundled with the app. What is dynamic lives in Room —
 * playback progress and My List — and is merged in here.
 *
 * Everything derived purely from the catalogue (sort orders, genre groupings) is computed **once**
 * and cached. Only the cheap decoration step re-runs when progress changes, because progress changes
 * every ten seconds during playback and a Fire TV Stick cannot afford to rebuild thousands of cards
 * each time.
 */
@Singleton
class MediaRepository @Inject constructor(
    private val catalog: BundledCatalog,
    private val searchHistoryDao: SearchHistoryDao,
    private val progressRepository: ProgressRepository,
    private val myListRepository: MyListRepository,
    private val timeProvider: TimeProvider,
    @Dispatcher(TorfilxDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /** Catalogue-derived structure, independent of local state. Built lazily, then kept. */
    private class CatalogViews(
        val byRecent: List<MediaItem>,
        val byTitle: List<MediaItem>,
        val byYear: List<MediaItem>,
        val byRating: List<MediaItem>,
        val genreRows: List<Pair<String, List<MediaItem>>>,
    )

    @Volatile
    private var views: CatalogViews? = null

    private fun views(): CatalogViews = views ?: synchronized(this) {
        views ?: buildViews().also { views = it }
    }

    private fun buildViews(): CatalogViews {
        val items = catalog.mediaItems()
        val byGenre = LinkedHashMap<String, MutableList<MediaItem>>()
        items.forEach { item ->
            item.genres.forEach { genre -> byGenre.getOrPut(genre) { ArrayList() }.add(item) }
        }
        return CatalogViews(
            byRecent = items.sortedByDescending { it.addedAtMs ?: it.year?.toLong() ?: 0L },
            byTitle = items.sortedBy { it.sortTitle.lowercase() },
            byYear = items.sortedByDescending { it.year ?: 0 },
            byRating = items.sortedByDescending { it.communityRating ?: 0.0 },
            genreRows = byGenre
                .filterValues { it.size >= MIN_GENRE_ROW_SIZE }
                .toList()
                .sortedBy { it.first }
                // distinctBy is belt-and-braces: a row must never contain the same film twice.
                .map { (genre, list) -> genre to list.distinctBy { item -> item.id }.take(MAX_ROW_ITEMS) },
        )
    }

    /** Cheap decoration: wraps pre-sorted items with the local state that actually changed. */
    private fun List<MediaItem>.toCards(
        progress: Map<String, PlaybackProgress>,
        myList: Set<String>,
    ): List<MediaCard> = map { item ->
        MediaCard(item = item, progress = progress[item.id], inMyList = item.id in myList)
    }

    // --- Library ---------------------------------------------------------------------------------

    fun observeLibrary(query: LibraryQuery): Flow<List<MediaCard>> = combine(
        progressRepository.observeAllProgress(),
        myListRepository.itemIds,
    ) { progress, myList ->
        val sorted = when (query.sort) {
            LibrarySort.RECENTLY_ADDED -> views().byRecent
            LibrarySort.ALPHABETICAL -> views().byTitle
            LibrarySort.YEAR -> views().byYear
            LibrarySort.RATING -> views().byRating
        }
        sorted.asSequence()
            .filter { item -> query.genre == null || query.genre in item.genres }
            .map { item ->
                MediaCard(item = item, progress = progress[item.id], inMyList = item.id in myList)
            }
            .filter { card -> query.watched.matches(card.progress) }
            .toList()
    }.flowOn(ioDispatcher)

    fun observeItemCount(): Flow<Int> = flowOf(catalog.mediaItems().size)

    suspend fun genres(): List<String> = withContext(ioDispatcher) { catalog.genres() }

    // --- Home ------------------------------------------------------------------------------------

    /**
     * Home rows: Continue Watching, the catalogue, My List, then a row per genre.
     *
     * Rows are capped at [MAX_ROW_ITEMS]: a row nobody can reach the end of with a D-pad costs
     * memory and scroll performance for nothing — the Movies grid is where the whole catalogue is
     * browsed.
     */
    fun observeHome(): Flow<List<HomeRow>> = combine(
        progressRepository.observeContinueWatching(),
        progressRepository.observeAllProgress(),
        myListRepository.itemIds,
    ) { continueWatching, progress, myListIds ->
        val data = views()

        buildList {
            if (continueWatching.isNotEmpty()) {
                add(
                    HomeRow(
                        id = ROW_CONTINUE_WATCHING,
                        title = "Continue Watching",
                        kind = HomeRowKind.CONTINUE_WATCHING,
                        items = continueWatching,
                    ),
                )
            }

            val recent = data.byRecent.take(MAX_ROW_ITEMS).toCards(progress, myListIds)
            if (recent.isNotEmpty()) {
                add(HomeRow(id = ROW_CATALOG, title = "Recently added", items = recent))
            }

            if (myListIds.isNotEmpty()) {
                val myListCards = data.byRecent
                    .asSequence()
                    .filter { it.id in myListIds }
                    .take(MAX_ROW_ITEMS)
                    .map { MediaCard(it, progress = progress[it.id], inMyList = true) }
                    .toList()
                if (myListCards.isNotEmpty()) {
                    add(
                        HomeRow(
                            id = ROW_MY_LIST,
                            title = "My List",
                            kind = HomeRowKind.MY_LIST,
                            items = myListCards,
                        ),
                    )
                }
            }

            data.genreRows.take(MAX_GENRE_ROWS).forEach { (genre, items) ->
                add(
                    HomeRow(
                        id = "genre-$genre",
                        title = genre,
                        kind = HomeRowKind.GENRE,
                        items = items.toCards(progress, myListIds),
                    ),
                )
            }
        }
    }.flowOn(ioDispatcher)

    // --- Details ---------------------------------------------------------------------------------

    fun observeItem(id: String): Flow<MediaItem?> = flowOf(catalog.item(id)?.item)

    suspend fun item(id: String): MediaItem? = withContext(ioDispatcher) { catalog.item(id)?.item }

    // --- Search ----------------------------------------------------------------------------------

    suspend fun search(query: String): List<SearchResult> = withContext(ioDispatcher) {
        val startedAt = System.nanoTime()
        val matches = catalog.search(query, SEARCH_LIMIT)
        if (matches.isEmpty()) return@withContext emptyList()

        val myListIds = myListRepository.itemIds.first()
        val progress = progressRepository.currentProgressMap()
        matches.map { item ->
            SearchResult(
                card = MediaCard(
                    item = item,
                    progress = progress[item.id],
                    inMyList = item.id in myListIds,
                ),
                matchedOn = "title",
            )
        }.also {
            // A slow search on a TV is felt immediately: the results grid lags behind the keyboard.
            // Logged when it happens so it is visible on the device, not only in a benchmark.
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            if (elapsedMs >= SLOW_SEARCH_MS) {
                TorfilxLog.w(TAG, "Search \"$query\" took ${elapsedMs}ms (${it.size} results)")
            } else {
                TorfilxLog.i(TAG, "Search \"$query\": ${it.size} results in ${elapsedMs}ms")
            }
        }
    }

    fun recentSearches(limit: Int = RECENT_SEARCH_LIMIT): Flow<List<String>> =
        searchHistoryDao.observeRecent(limit).map { entries -> entries.map { it.query } }

    suspend fun recordSearch(query: String) = withContext(ioDispatcher) {
        if (query.isNotBlank()) {
            searchHistoryDao.record(query.trim(), timeProvider.writeTimestampMs(), RECENT_SEARCH_LIMIT)
        }
    }

    suspend fun clearSearchHistory() = withContext(ioDispatcher) { searchHistoryDao.clear() }

    private fun WatchedFilter.matches(progress: PlaybackProgress?): Boolean = when (this) {
        WatchedFilter.ALL -> true
        WatchedFilter.WATCHED -> progress != null && progress.watched
        WatchedFilter.UNWATCHED -> progress == null || !progress.watched
    }

    companion object {
        const val ROW_CONTINUE_WATCHING = "continue-watching"
        const val ROW_MY_LIST = "my-list"
        const val ROW_CATALOG = "catalog"
        const val SEARCH_LIMIT = 60
        const val RECENT_SEARCH_LIMIT = 10
        private const val SLOW_SEARCH_MS = 50L
        private const val TAG = "MediaRepo"

        /** A TV row longer than this is unreachable by D-pad and only costs memory. */
        private const val MAX_ROW_ITEMS = 60
        private const val MAX_GENRE_ROWS = 12
        private const val MIN_GENRE_ROW_SIZE = 2
    }
}
