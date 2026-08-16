package com.myflix.core.data.remote

import com.myflix.core.model.DeviceCapabilities
import com.myflix.core.model.Episode
import com.myflix.core.model.HomeRow
import com.myflix.core.model.LibraryQuery
import com.myflix.core.model.PlaybackInfo
import com.myflix.core.model.PlaybackProgress
import com.myflix.core.model.SearchResult
import com.myflix.core.model.Season
import com.myflix.core.model.ServerInfo
import com.myflix.core.data.settings.SettingsRepository
import com.myflix.core.network.ItemDetailsResponse
import com.myflix.core.network.LibraryPage
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses between the demo library and the real server at runtime.
 *
 * Demo mode is an explicit user/debug setting, so switching to it never silently masks a broken
 * server connection (plan.md §10).
 */
@Singleton
class RoutingMediaRemoteSource @Inject constructor(
    private val network: NetworkMediaRemoteSource,
    private val demo: DemoMediaRemoteSource,
    private val settingsRepository: SettingsRepository,
) : MediaRemoteSource {

    // Read the flag per call rather than from a cached field: a request issued in the same frame as
    // the setting change must already use the new source, otherwise the first refresh after
    // switching to the demo library still hits the (unconfigured) server.
    private suspend fun delegate(): MediaRemoteSource =
        if (settingsRepository.demoMode.first()) demo else network

    override suspend fun serverInfo(): ServerInfo = delegate().serverInfo()

    override suspend fun home(etag: String?): Pair<List<HomeRow>, String?>? = delegate().home(etag)

    override suspend fun libraryItems(
        query: LibraryQuery,
        page: Int,
        pageSize: Int,
        sinceMs: Long?,
        etag: String?,
    ): LibraryPage? = delegate().libraryItems(query, page, pageSize, sinceMs, etag)

    override suspend fun itemDetails(id: String): ItemDetailsResponse = delegate().itemDetails(id)

    override suspend fun seasons(showId: String): List<Season> = delegate().seasons(showId)

    override suspend fun episodes(showId: String, seasonNumber: Int, seasonId: String): List<Episode> =
        delegate().episodes(showId, seasonNumber, seasonId)

    override suspend fun playbackInfo(itemId: String, capabilities: DeviceCapabilities): PlaybackInfo =
        delegate().playbackInfo(itemId, capabilities)

    override suspend fun progressSince(sinceMs: Long?): List<PlaybackProgress> =
        delegate().progressSince(sinceMs)

    override suspend fun putProgress(progress: PlaybackProgress) = delegate().putProgress(progress)

    override suspend fun deleteProgress(itemId: String) = delegate().deleteProgress(itemId)

    override suspend fun myList(): List<String> = delegate().myList()

    override suspend fun addToMyList(itemId: String) = delegate().addToMyList(itemId)

    override suspend fun removeFromMyList(itemId: String) = delegate().removeFromMyList(itemId)

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        delegate().search(query, limit)
}
