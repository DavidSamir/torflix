package com.torfilx.core.data.remote

import com.torfilx.core.model.DeviceCapabilities
import com.torfilx.core.model.Episode
import com.torfilx.core.model.HomeRow
import com.torfilx.core.model.LibraryQuery
import com.torfilx.core.model.PlaybackInfo
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.SearchResult
import com.torfilx.core.model.Season
import com.torfilx.core.model.ServerInfo
import com.torfilx.core.network.ItemDetailsResponse
import com.torfilx.core.network.LibraryPage

/**
 * Everything the repositories need from "the server".
 *
 * Two implementations exist: the real HTTP one, and an in-process demo library that lets the whole
 * UI — including every error and empty state — be built and tested before the server exists
 * (plan.md §0, §11).
 */
interface MediaRemoteSource {

    suspend fun serverInfo(): ServerInfo

    /** Null means "not modified" (HTTP 304). */
    suspend fun home(etag: String?): Pair<List<HomeRow>, String?>?

    suspend fun libraryItems(
        query: LibraryQuery = LibraryQuery(),
        page: Int = 0,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        sinceMs: Long? = null,
        etag: String? = null,
    ): LibraryPage?

    suspend fun itemDetails(id: String): ItemDetailsResponse

    suspend fun seasons(showId: String): List<Season>

    suspend fun episodes(showId: String, seasonNumber: Int, seasonId: String): List<Episode>

    suspend fun playbackInfo(itemId: String, capabilities: DeviceCapabilities): PlaybackInfo

    suspend fun progressSince(sinceMs: Long?): List<PlaybackProgress>

    suspend fun putProgress(progress: PlaybackProgress)

    suspend fun deleteProgress(itemId: String)

    suspend fun myList(): List<String>

    suspend fun addToMyList(itemId: String)

    suspend fun removeFromMyList(itemId: String)

    suspend fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<SearchResult>

    companion object {
        const val DEFAULT_PAGE_SIZE = 60
        const val DEFAULT_SEARCH_LIMIT = 40
    }
}
