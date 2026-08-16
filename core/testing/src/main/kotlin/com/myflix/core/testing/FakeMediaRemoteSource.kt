package com.myflix.core.testing

import com.myflix.core.common.error.DataError
import com.myflix.core.data.remote.MediaRemoteSource
import com.myflix.core.model.DeviceCapabilities
import com.myflix.core.model.Episode
import com.myflix.core.model.HomeRow
import com.myflix.core.model.LibraryQuery
import com.myflix.core.model.MediaCard
import com.myflix.core.model.MediaItem
import com.myflix.core.model.PlaybackInfo
import com.myflix.core.model.PlaybackProgress
import com.myflix.core.model.SearchResult
import com.myflix.core.model.Season
import com.myflix.core.model.ServerInfo
import com.myflix.core.network.ItemDetailsResponse
import com.myflix.core.network.LibraryPage

/**
 * A controllable stand-in for the server used by repository tests.
 *
 * Every method can be made to fail on demand, which is how the offline-first and conflict-resolution
 * behaviour is tested without a network.
 */
class FakeMediaRemoteSource : MediaRemoteSource {

    var items: MutableList<MediaItem> = mutableListOf()
    var seasons: MutableMap<String, List<Season>> = mutableMapOf()
    var episodes: MutableMap<String, List<Episode>> = mutableMapOf()
    var rows: List<HomeRow> = emptyList()
    var serverProgress: MutableList<PlaybackProgress> = mutableListOf()
    var myListIds: MutableSet<String> = mutableSetOf()
    var searchResults: List<SearchResult> = emptyList()
    var playbackInfo: PlaybackInfo? = null
    var info: ServerInfo = ServerInfo("Fake", "1.0", 1)

    /** When set, every call throws this. */
    var failure: (() -> DataError)? = null

    /** Set to simulate an HTTP 304 for `home`/`libraryItems`. */
    var notModified: Boolean = false

    val putProgressCalls = mutableListOf<PlaybackProgress>()
    val deleteProgressCalls = mutableListOf<String>()
    val addToMyListCalls = mutableListOf<String>()
    val removeFromMyListCalls = mutableListOf<String>()

    private fun gate() {
        failure?.let { throw it() }
    }

    override suspend fun serverInfo(): ServerInfo {
        gate()
        return info
    }

    override suspend fun home(etag: String?): Pair<List<HomeRow>, String?>? {
        gate()
        if (notModified) return null
        return rows to "etag-1"
    }

    override suspend fun libraryItems(
        query: LibraryQuery,
        page: Int,
        pageSize: Int,
        sinceMs: Long?,
        etag: String?,
    ): LibraryPage? {
        gate()
        if (notModified) return null
        val filtered = items.filter { sinceMs == null || it.updatedAtMs > sinceMs }
        val from = (page * pageSize).coerceAtMost(filtered.size)
        val to = (from + pageSize).coerceAtMost(filtered.size)
        return LibraryPage(filtered.subList(from, to), page, pageSize, filtered.size, "etag-$page")
    }

    override suspend fun itemDetails(id: String): ItemDetailsResponse {
        gate()
        val item = items.firstOrNull { it.id == id } ?: throw DataError.NotFound(id)
        return ItemDetailsResponse(item, seasons[id].orEmpty())
    }

    override suspend fun seasons(showId: String): List<Season> {
        gate()
        return seasons[showId].orEmpty()
    }

    override suspend fun episodes(showId: String, seasonNumber: Int, seasonId: String): List<Episode> {
        gate()
        return episodes[seasonId].orEmpty()
    }

    override suspend fun playbackInfo(itemId: String, capabilities: DeviceCapabilities): PlaybackInfo {
        gate()
        return playbackInfo ?: PlaybackInfo(itemId, emptyList())
    }

    override suspend fun progressSince(sinceMs: Long?): List<PlaybackProgress> {
        gate()
        return serverProgress.filter { sinceMs == null || it.updatedAtMs > sinceMs }
    }

    override suspend fun putProgress(progress: PlaybackProgress) {
        gate()
        putProgressCalls += progress
        serverProgress.removeAll { it.itemId == progress.itemId }
        serverProgress += progress
    }

    override suspend fun deleteProgress(itemId: String) {
        gate()
        deleteProgressCalls += itemId
        serverProgress.removeAll { it.itemId == itemId }
    }

    override suspend fun myList(): List<String> {
        gate()
        return myListIds.toList()
    }

    override suspend fun addToMyList(itemId: String) {
        gate()
        addToMyListCalls += itemId
        myListIds += itemId
    }

    override suspend fun removeFromMyList(itemId: String) {
        gate()
        removeFromMyListCalls += itemId
        myListIds -= itemId
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        gate()
        return searchResults.ifEmpty {
            items.filter { it.title.contains(query, ignoreCase = true) }
                .map { SearchResult(MediaCard(it), "title") }
        }
    }
}
