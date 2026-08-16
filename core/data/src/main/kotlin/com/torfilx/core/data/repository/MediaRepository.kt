package com.torfilx.core.data.repository

import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.data.catalog.BundledCatalog
import com.torfilx.core.data.database.SearchHistoryDao
import com.torfilx.core.common.time.TimeProvider
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library.
 *
 * There is exactly one source of titles: the catalogue bundled with the app
 * (`assets/catalog.json`). Nothing is fetched from a media server — the app has no server. What
 * *is* dynamic lives in Room: playback progress and My List, both local to this device.
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

    private fun catalogItems(): List<MediaItem> = catalog.items().map { it.item }

    // --- Library ---------------------------------------------------------------------------------

    fun observeLibrary(query: LibraryQuery): Flow<List<MediaCard>> = combine(
        progressRepository.observeAllProgress(),
        myListRepository.itemIds,
    ) { progress, myList ->
        catalogItems()
            .filter { item -> query.genre == null || query.genre in item.genres }
            .sortedWith(query.sort.comparator())
            .map { item ->
                MediaCard(item = item, progress = progress[item.id], inMyList = item.id in myList)
            }
            .filter { card -> query.watched.matches(card.progress) }
    }.flowOn(ioDispatcher)

    fun observeItemCount(): Flow<Int> = kotlinx.coroutines.flow.flowOf(catalogItems().size)

    suspend fun genres(): List<String> = withContext(ioDispatcher) {
        catalogItems().flatMap { it.genres }.distinct().sorted()
    }

    // --- Home ------------------------------------------------------------------------------------

    /**
     * Home rows, all derived locally: Continue Watching, My List and the catalogue itself.
     *
     * With no server there is no row ordering to fetch, so the rows are the ones that mean something
     * to the viewer: what they were watching, what they saved, and everything else by decade.
     */
    fun observeHome(): Flow<List<HomeRow>> = combine(
        progressRepository.observeContinueWatching(),
        myListCards(),
        progressRepository.observeAllProgress(),
        myListRepository.itemIds,
    ) { continueWatching, myList, progress, myListIds ->
        val all = catalogItems().map { item ->
            MediaCard(item = item, progress = progress[item.id], inMyList = item.id in myListIds)
        }

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
            if (all.isNotEmpty()) {
                add(HomeRow(id = ROW_CATALOG, title = "Public domain", items = all))
            }
            if (myList.isNotEmpty()) {
                add(
                    HomeRow(
                        id = ROW_MY_LIST,
                        title = "My List",
                        kind = HomeRowKind.MY_LIST,
                        items = myList,
                    ),
                )
            }
            // A row per genre, so a bigger catalogue still browses like a library.
            all.flatMap { it.item.genres }
                .distinct()
                .sorted()
                .forEach { genre ->
                    val items = all.filter { genre in it.item.genres }
                    if (items.size >= MIN_GENRE_ROW_SIZE) {
                        add(HomeRow(id = "genre-$genre", title = genre, kind = HomeRowKind.GENRE, items = items))
                    }
                }
        }
    }.flowOn(ioDispatcher)

    private fun myListCards(): Flow<List<MediaCard>> =
        combine(myListRepository.itemIds, progressRepository.observeAllProgress()) { ids, progress ->
            catalogItems()
                .filter { it.id in ids }
                .map { MediaCard(it, progress = progress[it.id], inMyList = true) }
        }

    // --- Details ---------------------------------------------------------------------------------

    fun observeItem(id: String): Flow<MediaItem?> =
        kotlinx.coroutines.flow.flowOf(catalog.item(id)?.item)

    suspend fun item(id: String): MediaItem? = withContext(ioDispatcher) { catalog.item(id)?.item }

    // --- Search ----------------------------------------------------------------------------------

    suspend fun search(query: String): List<SearchResult> = withContext(ioDispatcher) {
        val needle = query.trim()
        if (needle.isBlank()) return@withContext emptyList()
        val myListIds = myListRepository.itemIds.first()
        val progress = progressRepository.currentProgressMap()

        catalogItems()
            .filter { it.title.contains(needle, ignoreCase = true) }
            .sortedWith(
                compareBy(
                    { !it.title.startsWith(needle, ignoreCase = true) },
                    { it.sortTitle.lowercase() },
                ),
            )
            .map { item ->
                SearchResult(
                    card = MediaCard(
                        item = item,
                        progress = progress[item.id],
                        inMyList = item.id in myListIds,
                    ),
                    matchedOn = "title",
                )
            }
    }

    fun recentSearches(limit: Int = RECENT_SEARCH_LIMIT): Flow<List<String>> =
        searchHistoryDao.observeRecent(limit).map { entries -> entries.map { it.query } }

    suspend fun recordSearch(query: String) = withContext(ioDispatcher) {
        if (query.isNotBlank()) {
            searchHistoryDao.record(query.trim(), timeProvider.nowMs(), RECENT_SEARCH_LIMIT)
        }
    }

    suspend fun clearSearchHistory() = withContext(ioDispatcher) { searchHistoryDao.clear() }

    private fun LibrarySort.comparator(): Comparator<MediaItem> = when (this) {
        LibrarySort.RECENTLY_ADDED -> compareByDescending { it.addedAtMs ?: it.year?.toLong() ?: 0L }
        LibrarySort.ALPHABETICAL -> compareBy { it.sortTitle.lowercase() }
        LibrarySort.YEAR -> compareByDescending { it.year ?: 0 }
        LibrarySort.RATING -> compareByDescending { it.communityRating ?: 0.0 }
    }

    private fun WatchedFilter.matches(progress: PlaybackProgress?): Boolean = when (this) {
        WatchedFilter.ALL -> true
        WatchedFilter.WATCHED -> progress != null && progress.watched
        WatchedFilter.UNWATCHED -> progress == null || !progress.watched
    }

    companion object {
        const val ROW_CONTINUE_WATCHING = "continue-watching"
        const val ROW_MY_LIST = "my-list"
        const val ROW_CATALOG = "catalog"
        const val RECENT_SEARCH_LIMIT = 10
        private const val MIN_GENRE_ROW_SIZE = 2
    }
}
