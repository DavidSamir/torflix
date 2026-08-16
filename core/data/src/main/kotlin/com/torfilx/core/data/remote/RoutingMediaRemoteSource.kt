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
import com.torfilx.core.data.catalog.BundledCatalog
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.network.ItemDetailsResponse
import com.torfilx.core.network.LibraryPage
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
    private val catalog: BundledCatalog,
    private val settingsRepository: SettingsRepository,
) : MediaRemoteSource {

    // Read the flag per call rather than from a cached field: a request issued in the same frame as
    // the setting change must already use the new source, otherwise the first refresh after
    // switching to the demo library still hits the (unconfigured) server.
    private suspend fun delegate(): MediaRemoteSource =
        if (settingsRepository.demoMode.first()) demo else network

    override suspend fun serverInfo(): ServerInfo = delegate().serverInfo()

    /**
     * Home is hybrid: whatever the server offers, plus a row of the bundled titles.
     *
     * The bundled row survives a dead server — that is the point of shipping it — so it is appended
     * even when the server call fails outright.
     */
    override suspend fun home(etag: String?): Pair<List<HomeRow>, String?>? {
        val catalogRow = catalogRow()
        val serverRows = runCatching { delegate().home(etag) }.getOrNull()
        if (serverRows == null && catalogRow == null) return null
        val rows = (serverRows?.first.orEmpty()) + listOfNotNull(catalogRow)
        return rows to (serverRows?.second)
    }

    override suspend fun libraryItems(
        query: LibraryQuery,
        page: Int,
        pageSize: Int,
        sinceMs: Long?,
        etag: String?,
    ): LibraryPage? {
        val serverPage = runCatching { delegate().libraryItems(query, page, pageSize, sinceMs, etag) }
            .getOrElse { error ->
                // A missing server must not hide the bundled titles.
                if (catalogItems(query).isEmpty()) throw error else null
            }
        // Bundled titles are only added on the first page; they are a fixed, small set.
        val catalogPart = if (page == 0) catalogItems(query) else emptyList()
        if (serverPage == null && catalogPart.isEmpty()) return null

        val items = (serverPage?.items.orEmpty() + catalogPart).distinctBy { it.id }
        return LibraryPage(
            items = items,
            page = serverPage?.page ?: page,
            pageSize = serverPage?.pageSize ?: pageSize,
            total = (serverPage?.total ?: 0) + catalogPart.size,
            etag = serverPage?.etag,
        )
    }

    override suspend fun itemDetails(id: String): ItemDetailsResponse {
        catalog.item(id)?.let { return ItemDetailsResponse(it.item, emptyList()) }
        return delegate().itemDetails(id)
    }

    private fun catalogItems(query: LibraryQuery): List<com.torfilx.core.model.MediaItem> {
        if (query.type == com.torfilx.core.model.MediaType.SHOW) return emptyList()
        return catalog.items()
            .map { it.item }
            .filter { item -> query.genre == null || query.genre in item.genres }
    }

    private fun catalogRow(): HomeRow? {
        val items = catalog.items()
        if (items.isEmpty()) return null
        return HomeRow(
            id = CATALOG_ROW_ID,
            title = "Public domain",
            items = items.map { com.torfilx.core.model.MediaCard(it.item) },
        )
    }

    override suspend fun seasons(showId: String): List<Season> = delegate().seasons(showId)

    override suspend fun episodes(showId: String, seasonNumber: Int, seasonId: String): List<Episode> =
        delegate().episodes(showId, seasonNumber, seasonId)

    /**
     * Playback info is where "hybrid" actually pays off: the server's sources and the catalogue's
     * magnets are offered together, so the user can fall back to the swarm when the PC is off — and
     * a bundled title works even though the server has never heard of it.
     */
    override suspend fun playbackInfo(itemId: String, capabilities: DeviceCapabilities): PlaybackInfo {
        val torrentSources = catalog.sourcesFor(itemId)

        val serverInfo = runCatching { delegate().playbackInfo(itemId, capabilities) }
            .getOrElse { error ->
                if (torrentSources.isEmpty()) throw error else null
            }

        if (serverInfo == null) {
            return PlaybackInfo(itemId = itemId, sources = torrentSources)
        }
        return serverInfo.copy(sources = serverInfo.sources + torrentSources)
    }

    override suspend fun progressSince(sinceMs: Long?): List<PlaybackProgress> =
        delegate().progressSince(sinceMs)

    override suspend fun putProgress(progress: PlaybackProgress) = delegate().putProgress(progress)

    override suspend fun deleteProgress(itemId: String) = delegate().deleteProgress(itemId)

    override suspend fun myList(): List<String> = delegate().myList()

    override suspend fun addToMyList(itemId: String) = delegate().addToMyList(itemId)

    override suspend fun removeFromMyList(itemId: String) = delegate().removeFromMyList(itemId)

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val needle = query.trim()
        val catalogHits = catalog.items()
            .filter { it.item.title.contains(needle, ignoreCase = true) }
            .map { SearchResult(com.torfilx.core.model.MediaCard(it.item), matchedOn = "title") }
        val serverHits = runCatching { delegate().search(query, limit) }
            .getOrElse { error -> if (catalogHits.isEmpty()) throw error else emptyList() }
        return (serverHits + catalogHits).distinctBy { it.card.item.id }.take(limit)
    }

    companion object {
        const val CATALOG_ROW_ID = "bundled-catalog"
    }
}
