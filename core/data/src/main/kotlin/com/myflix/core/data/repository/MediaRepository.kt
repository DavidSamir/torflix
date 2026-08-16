package com.myflix.core.data.repository

import com.myflix.core.common.di.Dispatcher
import com.myflix.core.common.di.MyflixDispatcher
import com.myflix.core.common.error.DataError
import com.myflix.core.common.log.MyflixLog
import com.myflix.core.common.time.TimeProvider
import com.myflix.core.data.database.EpisodeDao
import com.myflix.core.data.database.LibraryDao
import com.myflix.core.data.database.SeasonDao
import com.myflix.core.data.database.SearchHistoryDao
import com.myflix.core.data.database.SyncMarkerDao
import com.myflix.core.data.database.SyncMarkerEntity
import com.myflix.core.data.database.toDomain
import com.myflix.core.data.database.toEntity
import com.myflix.core.data.remote.MediaRemoteSource
import com.myflix.core.model.Episode
import com.myflix.core.model.HomeRow
import com.myflix.core.model.HomeRowKind
import com.myflix.core.model.LibraryQuery
import com.myflix.core.model.LibrarySort
import com.myflix.core.model.MediaCard
import com.myflix.core.model.MediaItem
import com.myflix.core.model.MediaType
import com.myflix.core.model.PlaybackProgress
import com.myflix.core.model.SearchResult
import com.myflix.core.model.Season
import com.myflix.core.model.ServerInfo
import com.myflix.core.model.ShowDetails
import com.myflix.core.model.WatchedFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MediaRepo"

/**
 * Single source of truth for library content.
 *
 * Reads always come from Room, so screens render instantly and keep working when the server is
 * unreachable; the network is only ever a *refresh* (plan.md §3).
 */
@Singleton
class MediaRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val seasonDao: SeasonDao,
    private val episodeDao: EpisodeDao,
    private val syncMarkerDao: SyncMarkerDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val remote: MediaRemoteSource,
    private val progressRepository: ProgressRepository,
    private val myListRepository: MyListRepository,
    private val timeProvider: TimeProvider,
    @Dispatcher(MyflixDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    // --- Library ---------------------------------------------------------------------------------

    fun observeLibrary(query: LibraryQuery): Flow<List<MediaCard>> =
        combine(
            libraryDao.observeLibrary(
                type = query.type?.name,
                genre = query.genre,
                sortKey = query.sort.sortKey(),
            ),
            progressRepository.observeAllProgress(),
            myListRepository.itemIds,
        ) { items, progress, myList ->
            items
                .map { it.toDomain() }
                .map { item ->
                    MediaCard(
                        item = item,
                        progress = progress[item.id],
                        inMyList = item.id in myList,
                    )
                }
                .filter { card -> query.watched.matches(card.progress) }
        }.flowOn(ioDispatcher)

    fun observeItemCount(): Flow<Int> = libraryDao.observeCount()

    suspend fun genres(): List<String> = withContext(ioDispatcher) {
        libraryDao.allGenreBlobs()
            .flatMap { blob -> if (blob.isBlank()) emptyList() else blob.split("") }
            .distinct()
            .sorted()
    }

    /**
     * Delta refresh: only items changed since the newest `updatedAt` we already hold are requested,
     * which keeps the refresh cheap on a library of thousands of files (plan.md §8.2).
     */
    suspend fun refreshLibrary(full: Boolean = false): Int = withContext(ioDispatcher) {
        val since = if (full) null else libraryDao.maxUpdatedAt()?.takeIf { it > 0 }
        var page = 0
        var fetched = 0
        while (true) {
            val result = remote.libraryItems(
                query = LibraryQuery(),
                page = page,
                sinceMs = since,
            ) ?: break // 304: nothing changed

            if (result.items.isEmpty()) break
            libraryDao.upsertAll(result.items.map { it.toEntity() })
            fetched += result.items.size
            page++
            if (fetched >= result.total || result.items.size < result.pageSize) break
            if (page > MAX_PAGES) {
                MyflixLog.w(TAG, "Library refresh stopped at $MAX_PAGES pages")
                break
            }
        }
        syncMarkerDao.upsert(
            SyncMarkerEntity(key = MARKER_LIBRARY, lastSyncedAtMs = timeProvider.serverAdjustedNowMs()),
        )
        fetched
    }

    // --- Home ------------------------------------------------------------------------------------

    /**
     * Home rows, assembled from the cached server rows plus the two rows the app owns locally.
     *
     * Continue Watching and My List come from Room so they update the instant the user returns from
     * the player, without waiting for a server round trip (plan.md §11.3).
     */
    fun observeHome(): Flow<List<HomeRow>> = combine(
        cachedServerRows(),
        progressRepository.observeContinueWatching(),
        myListCards(),
        progressRepository.observeAllProgress(),
        myListRepository.itemIds,
    ) { serverRows, continueWatching, myList, progress, myListIds ->
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
            serverRows.forEach { row ->
                add(row.copy(items = row.items.map { it.decorate(progress, myListIds) }))
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
        }
    }.flowOn(ioDispatcher)

    /**
     * Server-provided rows are cached in memory only: they are cheap to refetch, and stale row
     * *ordering* is less useful than a fresh library. The items they reference live in Room.
     */
    @Volatile
    private var cachedRows: List<HomeRow> = emptyList()

    private fun cachedServerRows(): Flow<List<HomeRow>> =
        libraryDao.observeCount().map { count ->
            if (count == 0) emptyList() else cachedRows
        }

    suspend fun refreshHome(): List<HomeRow> = withContext(ioDispatcher) {
        val marker = syncMarkerDao.get(MARKER_HOME)
        val response = remote.home(marker?.etag)
        if (response == null) return@withContext cachedRows // 304

        val (rows, etag) = response
        // Persist every referenced item so details/playback work offline afterwards.
        val items = rows.flatMap { row -> row.items.map { it.item } }.distinctBy { it.id }
        if (items.isNotEmpty()) libraryDao.upsertAll(items.map { it.toEntity() })

        cachedRows = rows.filterNot {
            it.kind == HomeRowKind.CONTINUE_WATCHING || it.kind == HomeRowKind.MY_LIST
        }
        syncMarkerDao.upsert(
            SyncMarkerEntity(
                key = MARKER_HOME,
                etag = etag,
                lastSyncedAtMs = timeProvider.serverAdjustedNowMs(),
            ),
        )
        cachedRows
    }

    private fun myListCards(): Flow<List<MediaCard>> =
        combine(myListRepository.itemIds, progressRepository.observeAllProgress()) { ids, progress ->
            if (ids.isEmpty()) {
                emptyList()
            } else {
                libraryDao.getItems(ids.toList())
                    .map { it.toDomain() }
                    .map { MediaCard(it, progress = progress[it.id], inMyList = true) }
            }
        }

    // --- Details ---------------------------------------------------------------------------------

    fun observeItem(id: String): Flow<MediaItem?> = libraryDao.observeItem(id).map { it?.toDomain() }

    suspend fun item(id: String): MediaItem? = withContext(ioDispatcher) {
        libraryDao.getItem(id)?.toDomain()
    }

    /** Cached show structure; emits before any network call so the screen never starts blank. */
    fun observeShowDetails(showId: String): Flow<ShowDetails?> = combine(
        libraryDao.observeItem(showId),
        seasonDao.observeForShow(showId),
        episodeDao.observeForShow(showId),
    ) { item, seasons, episodes ->
        val show = item?.toDomain() ?: return@combine null
        ShowDetails(
            show = show,
            seasons = seasons.map { it.toDomain() },
            episodesBySeason = episodes.map { it.toDomain() }.groupBy { it.seasonId },
        )
    }.flowOn(ioDispatcher)

    /** Fetches item metadata (and, for shows, seasons + all episodes) and stores it locally. */
    suspend fun refreshDetails(id: String): MediaItem = withContext(ioDispatcher) {
        val response = remote.itemDetails(id)
        libraryDao.upsertAll(listOf(response.item.toEntity()))

        if (response.item.type == MediaType.SHOW) {
            val seasons = response.seasons.ifEmpty { remote.seasons(id) }
            seasonDao.upsertAll(seasons.map { it.toEntity() })
            seasons.forEach { season ->
                val episodes = runCatching { remote.episodes(id, season.number, season.id) }
                    .onFailure { MyflixLog.w(TAG, "Episodes failed for ${season.id}", it) }
                    .getOrDefault(emptyList())
                if (episodes.isNotEmpty()) episodeDao.upsertAll(episodes.map { it.toEntity() })
            }
        }
        response.item
    }

    suspend fun refreshSeasonEpisodes(showId: String, season: Season): List<Episode> =
        withContext(ioDispatcher) {
            val episodes = remote.episodes(showId, season.number, season.id)
            if (episodes.isNotEmpty()) episodeDao.upsertAll(episodes.map { it.toEntity() })
            episodes
        }

    suspend fun episode(episodeId: String): Episode? = withContext(ioDispatcher) {
        episodeDao.getEpisode(episodeId)?.toDomain()
    }

    // --- Search ----------------------------------------------------------------------------------

    /**
     * Searches the server, falling back to the local cache when the server is unreachable so search
     * still works on a sleeping-PC LAN (plan.md §10).
     */
    suspend fun search(query: String): List<SearchResult> = withContext(ioDispatcher) {
        if (query.isBlank()) return@withContext emptyList()
        val myListIds = myListRepository.itemIds.first()
        val progress = progressRepository.currentProgressMap()

        val remoteResults = runCatching { remote.search(query) }
        if (remoteResults.isSuccess) {
            val results = remoteResults.getOrThrow()
            val items = results.map { it.card.item }.distinctBy { it.id }
            if (items.isNotEmpty()) libraryDao.upsertAll(items.map { it.toEntity() })
            return@withContext results.map { result ->
                result.copy(card = result.card.decorate(progress, myListIds))
            }
        }

        MyflixLog.w(TAG, "Remote search failed, using local cache", remoteResults.exceptionOrNull())
        libraryDao.searchOffline(query.trim(), SEARCH_LIMIT)
            .map { it.toDomain() }
            .map { SearchResult(MediaCard(it).decorate(progress, myListIds), matchedOn = "title") }
    }

    fun recentSearches(limit: Int = RECENT_SEARCH_LIMIT): Flow<List<String>> =
        searchHistoryDao.observeRecent(limit).map { entries -> entries.map { it.query } }

    suspend fun recordSearch(query: String) = withContext(ioDispatcher) {
        if (query.isNotBlank()) {
            searchHistoryDao.record(query.trim(), timeProvider.nowMs(), RECENT_SEARCH_LIMIT)
        }
    }

    suspend fun clearSearchHistory() = withContext(ioDispatcher) { searchHistoryDao.clear() }

    // --- Maintenance -----------------------------------------------------------------------------

    suspend fun serverInfo(): ServerInfo = remote.serverInfo()

    suspend fun clearCache() = withContext(ioDispatcher) {
        libraryDao.clear()
        syncMarkerDao.clear()
        cachedRows = emptyList()
    }

    /** Item deleted on the server: drop it locally so it disappears from every row at once. */
    suspend fun forgetItem(id: String) = withContext(ioDispatcher) {
        libraryDao.delete(id)
        seasonDao.deleteForShow(id)
        episodeDao.deleteForShow(id)
    }

    private fun MediaCard.decorate(
        progress: Map<String, PlaybackProgress>,
        myListIds: Set<String>,
    ): MediaCard = copy(
        progress = progress[playableId] ?: progress[item.id],
        inMyList = item.id in myListIds,
    )

    private fun LibrarySort.sortKey(): Int = when (this) {
        LibrarySort.RECENTLY_ADDED -> 0
        LibrarySort.ALPHABETICAL -> 1
        LibrarySort.YEAR -> 2
        LibrarySort.RATING -> 3
    }

    private fun WatchedFilter.matches(progress: PlaybackProgress?): Boolean = when (this) {
        WatchedFilter.ALL -> true
        WatchedFilter.WATCHED -> progress != null && progress.watched
        WatchedFilter.UNWATCHED -> progress == null || !progress.watched
    }

    companion object {
        const val ROW_CONTINUE_WATCHING = "continue-watching"
        const val ROW_MY_LIST = "my-list"
        const val MARKER_HOME = "home"
        const val MARKER_LIBRARY = "library"
        const val SEARCH_LIMIT = 40
        const val RECENT_SEARCH_LIMIT = 10
        private const val MAX_PAGES = 200
    }
}
