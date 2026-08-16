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
import com.torfilx.core.network.RemoteMediaDataSource
import javax.inject.Inject
import javax.inject.Singleton

/** [MediaRemoteSource] backed by the real HTTP API. */
@Singleton
class NetworkMediaRemoteSource @Inject constructor(
    private val remote: RemoteMediaDataSource,
) : MediaRemoteSource {

    override suspend fun serverInfo(): ServerInfo = remote.serverInfo()

    override suspend fun home(etag: String?): Pair<List<HomeRow>, String?>? = remote.home(etag)

    override suspend fun libraryItems(
        query: LibraryQuery,
        page: Int,
        pageSize: Int,
        sinceMs: Long?,
        etag: String?,
    ): LibraryPage? = remote.libraryItems(query, page, pageSize, sinceMs, etag)

    override suspend fun itemDetails(id: String): ItemDetailsResponse = remote.itemDetails(id)

    override suspend fun seasons(showId: String): List<Season> = remote.seasons(showId)

    override suspend fun episodes(showId: String, seasonNumber: Int, seasonId: String): List<Episode> =
        remote.episodes(showId, seasonNumber, seasonId)

    override suspend fun playbackInfo(itemId: String, capabilities: DeviceCapabilities): PlaybackInfo =
        remote.playbackInfo(itemId, capabilities)

    override suspend fun progressSince(sinceMs: Long?): List<PlaybackProgress> =
        remote.progressSince(sinceMs)

    override suspend fun putProgress(progress: PlaybackProgress) = remote.putProgress(progress)

    override suspend fun deleteProgress(itemId: String) = remote.deleteProgress(itemId)

    override suspend fun myList(): List<String> = remote.myList()

    override suspend fun addToMyList(itemId: String) = remote.addToMyList(itemId)

    override suspend fun removeFromMyList(itemId: String) = remote.removeFromMyList(itemId)

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        remote.search(query, limit)
}
