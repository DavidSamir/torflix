package com.myflix.core.data.remote

import com.myflix.core.common.error.DataError
import com.myflix.core.model.DeviceCapabilities
import com.myflix.core.model.Episode
import com.myflix.core.model.HomeRow
import com.myflix.core.model.HomeRowKind
import com.myflix.core.model.LibraryQuery
import com.myflix.core.model.LibrarySort
import com.myflix.core.model.MediaCard
import com.myflix.core.model.MediaType
import com.myflix.core.model.PlaybackInfo
import com.myflix.core.model.PlaybackProgress
import com.myflix.core.model.SearchResult
import com.myflix.core.model.Season
import com.myflix.core.model.ServerFeature
import com.myflix.core.model.ServerInfo
import com.myflix.core.network.ItemDetailsResponse
import com.myflix.core.network.LibraryPage
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process implementation of the server contract, backed by [DemoLibrary].
 *
 * Latency and failures are injectable so every loading, empty and error state in the UI can be
 * driven deliberately from the debug drawer (plan.md §11, §12).
 */
@Singleton
class DemoMediaRemoteSource @Inject constructor() : MediaRemoteSource {

    /** Artificial round-trip latency, in milliseconds. */
    @Volatile
    var latencyMs: Long = 120

    /** When set, every call fails with this error — used to exercise error states. */
    @Volatile
    var failure: (() -> DataError)? = null

    /** Endpoints named here fail while the rest keep working (single failing row test). */
    val failingEndpoints: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Points every demo source at a real, playable file.
     *
     * The demo library itself has no media behind it, so this is how playback (decoding, seeking,
     * progress saving) is exercised on a device without a media server (plan.md §12).
     */
    @Volatile
    var mediaUrlOverride: String? = null

    private val progress = ConcurrentHashMap<String, PlaybackProgress>()
    private val myList = ConcurrentHashMap.newKeySet<String>()

    private suspend fun gate(endpoint: String) {
        if (latencyMs > 0) delay(latencyMs)
        failure?.let { throw it() }
        if (failingEndpoints.contains(endpoint)) {
            throw DataError.ServerError(500, "demo", "Injected failure for $endpoint")
        }
    }

    override suspend fun serverInfo(): ServerInfo {
        gate("server/info")
        return ServerInfo(
            name = "Demo library",
            version = "demo",
            apiVersion = 1,
            features = setOf(ServerFeature.TRANSCODE, ServerFeature.SPRITES, ServerFeature.MARKERS),
        )
    }

    override suspend fun home(etag: String?): Pair<List<HomeRow>, String?> {
        gate("home")
        val all = DemoLibrary.items
        val rows = buildList {
            add(
                HomeRow(
                    id = "recently-added",
                    title = "Recently Added",
                    kind = HomeRowKind.RECENTLY_ADDED,
                    items = all.sortedByDescending { it.addedAtMs ?: 0 }.take(20).map { MediaCard(it) },
                ),
            )
            add(
                HomeRow(
                    id = "movies",
                    title = "Movies",
                    items = all.filter { it.isMovie }.take(24).map { MediaCard(it) },
                ),
            )
            add(
                HomeRow(
                    id = "shows",
                    title = "TV Shows",
                    items = all.filter { it.isShow }.map { MediaCard(it) },
                ),
            )
            DemoLibrary.genresInLibrary().take(5).forEach { genre ->
                add(
                    HomeRow(
                        id = "genre-$genre",
                        title = genre,
                        kind = HomeRowKind.GENRE,
                        items = all.filter { genre in it.genres }.take(20).map { MediaCard(it) },
                    ),
                )
            }
        }
        return rows to "demo-etag"
    }

    override suspend fun libraryItems(
        query: LibraryQuery,
        page: Int,
        pageSize: Int,
        sinceMs: Long?,
        etag: String?,
    ): LibraryPage {
        gate("library/items")
        var items = DemoLibrary.items
        query.type?.let { type -> items = items.filter { it.type == type } }
        query.genre?.let { genre -> items = items.filter { genre in it.genres } }
        sinceMs?.let { since -> items = items.filter { it.updatedAtMs > since } }
        items = when (query.sort) {
            LibrarySort.RECENTLY_ADDED -> items.sortedByDescending { it.addedAtMs ?: 0 }
            LibrarySort.ALPHABETICAL -> items.sortedBy { it.sortTitle.lowercase() }
            LibrarySort.YEAR -> items.sortedByDescending { it.year ?: 0 }
            LibrarySort.RATING -> items.sortedByDescending { it.communityRating ?: 0.0 }
        }
        val from = (page * pageSize).coerceAtMost(items.size)
        val to = (from + pageSize).coerceAtMost(items.size)
        return LibraryPage(
            items = items.subList(from, to),
            page = page,
            pageSize = pageSize,
            total = items.size,
            etag = "demo-etag-$page",
        )
    }

    override suspend fun itemDetails(id: String): ItemDetailsResponse {
        gate("library/items/{id}")
        val item = DemoLibrary.item(id) ?: throw DataError.NotFound(id, "No demo item with id $id")
        return ItemDetailsResponse(item, DemoLibrary.seasonsByShow[id].orEmpty())
    }

    override suspend fun seasons(showId: String): List<Season> {
        gate("shows/{id}/seasons")
        return DemoLibrary.seasonsByShow[showId].orEmpty()
    }

    override suspend fun episodes(showId: String, seasonNumber: Int, seasonId: String): List<Episode> {
        gate("shows/{id}/seasons/{n}/episodes")
        return DemoLibrary.episodesBySeason[seasonId].orEmpty()
    }

    override suspend fun playbackInfo(itemId: String, capabilities: DeviceCapabilities): PlaybackInfo {
        gate("items/{id}/playback-info")
        val durationMs = DemoLibrary.item(itemId)?.runtimeMs
            ?: DemoLibrary.episode(itemId)?.runtimeMs
            ?: (90 * 60_000L)
        val info = DemoLibrary.playbackInfo(itemId, durationMs).copy(resume = progress[itemId])
        val override = mediaUrlOverride ?: return info
        return info.copy(sources = info.sources.map { it.copy(url = override) })
    }

    override suspend fun progressSince(sinceMs: Long?): List<PlaybackProgress> {
        gate("progress")
        return progress.values.filter { sinceMs == null || it.updatedAtMs > sinceMs }
    }

    override suspend fun putProgress(progress: PlaybackProgress) {
        gate("progress/{id}")
        this.progress[progress.itemId] = progress
    }

    override suspend fun deleteProgress(itemId: String) {
        gate("progress/{id}")
        progress.remove(itemId)
    }

    override suspend fun myList(): List<String> {
        gate("my-list")
        return myList.toList()
    }

    override suspend fun addToMyList(itemId: String) {
        gate("my-list/{id}")
        myList.add(itemId)
    }

    override suspend fun removeFromMyList(itemId: String) {
        gate("my-list/{id}")
        myList.remove(itemId)
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        gate("search")
        if (query.isBlank()) return emptyList()
        val needle = query.trim().lowercase()
        val items = DemoLibrary.items
            .filter { it.title.lowercase().contains(needle) }
            .sortedWith(
                compareBy(
                    { !it.title.lowercase().startsWith(needle) },
                    { it.sortTitle.lowercase() },
                ),
            )
            .take(limit)
            .map { SearchResult(MediaCard(it), matchedOn = "title") }

        val episodeMatches = DemoLibrary.allEpisodes
            .filter { it.title?.lowercase()?.contains(needle) == true }
            .take(5)
            .mapNotNull { episode ->
                DemoLibrary.item(episode.showId)?.let { show ->
                    SearchResult(MediaCard(show, episode = episode), matchedOn = "episode")
                }
            }

        return (items + episodeMatches).take(limit)
    }

    /** Resets injected failures and stored state; used between tests. */
    fun reset() {
        latencyMs = 0
        failure = null
        failingEndpoints.clear()
        progress.clear()
        myList.clear()
    }
}
