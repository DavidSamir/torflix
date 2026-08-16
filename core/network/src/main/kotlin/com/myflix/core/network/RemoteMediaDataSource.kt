package com.myflix.core.network

import com.myflix.core.model.DeviceCapabilities
import com.myflix.core.model.Episode
import com.myflix.core.model.HomeRow
import com.myflix.core.model.LibraryQuery
import com.myflix.core.model.LibrarySort
import com.myflix.core.model.MediaItem
import com.myflix.core.model.MediaType
import com.myflix.core.model.PlaybackInfo
import com.myflix.core.model.PlaybackProgress
import com.myflix.core.model.Season
import com.myflix.core.model.SearchResult
import com.myflix.core.model.ServerInfo
import com.myflix.core.model.WatchedFilter
import com.myflix.core.model.MediaCard
import com.myflix.core.network.api.ApiResult
import com.myflix.core.network.api.MyflixApiService
import com.myflix.core.network.api.apiCall
import com.myflix.core.network.api.apiCallBody
import com.myflix.core.network.dto.CapabilitiesDto
import com.myflix.core.network.dto.ProgressUpdateDto
import com.myflix.core.network.mapper.mapItemsSkippingBad
import com.myflix.core.network.mapper.toDomain
import com.myflix.core.network.mapper.toDto
import com.myflix.core.network.mapper.toIsoUtc
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** One page of library items plus the ETag that produced it. */
data class LibraryPage(
    val items: List<MediaItem>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val etag: String? = null,
)

data class ItemDetailsResponse(
    val item: MediaItem,
    val seasons: List<Season>,
)

/**
 * The only place that talks HTTP. Returns domain types so the repository layer never sees a DTO.
 * Failures are thrown as `DataError` by [apiCall].
 */
@Singleton
class RemoteMediaDataSource @Inject constructor(
    private val api: MyflixApiService,
    private val json: Json,
) {

    suspend fun serverInfo(): ServerInfo = apiCallBody(json) { api.serverInfo() }.toDomain()

    /** Returns null when the server answers 304 (nothing changed since [etag]). */
    suspend fun home(etag: String?): Pair<List<HomeRow>, String?>? =
        when (val result = apiCall(json) { api.home(etag) }) {
            ApiResult.NotModified -> null
            is ApiResult.Ok -> mapItemsSkippingBad(result.body.rows) { it.toDomain() } to result.etag
        }

    suspend fun libraryItems(
        query: LibraryQuery = LibraryQuery(),
        page: Int = 0,
        pageSize: Int = MyflixApiService.PAGE_SIZE,
        sinceMs: Long? = null,
        etag: String? = null,
    ): LibraryPage? {
        val result = apiCall(json) {
            api.libraryItems(
                type = query.type?.wire(),
                genre = query.genre,
                sort = query.sort.wire(),
                watched = query.watched.wire(),
                sinceIso = sinceMs?.toIsoUtc(),
                page = page,
                pageSize = pageSize,
                etag = etag,
            )
        }
        return when (result) {
            ApiResult.NotModified -> null
            is ApiResult.Ok -> LibraryPage(
                items = mapItemsSkippingBad(result.body.items) { it.toDomain() },
                page = result.body.page,
                pageSize = result.body.pageSize,
                total = result.body.total,
                etag = result.etag,
            )
        }
    }

    suspend fun itemDetails(id: String): ItemDetailsResponse {
        val dto = apiCallBody(json, itemId = id) { api.itemDetails(id) }
        return ItemDetailsResponse(
            item = dto.item.toDomain(),
            seasons = mapItemsSkippingBad(dto.seasons) { it.toDomain(id) }
                .sortedWith(compareBy({ it.isSpecials }, { it.number })),
        )
    }

    suspend fun seasons(showId: String): List<Season> =
        mapItemsSkippingBad(apiCallBody(json, itemId = showId) { api.seasons(showId) }.items) {
            it.toDomain(showId)
        }.sortedWith(compareBy({ it.isSpecials }, { it.number }))

    suspend fun episodes(showId: String, seasonNumber: Int, seasonId: String): List<Episode> =
        mapItemsSkippingBad(
            apiCallBody(json, itemId = showId) { api.episodes(showId, seasonNumber) }.items,
        ) { it.toDomain(showId, seasonId) }
            .sortedBy { it.episodeNumber }

    suspend fun playbackInfo(itemId: String, capabilities: DeviceCapabilities): PlaybackInfo {
        val capsJson = json.encodeToString(CapabilitiesDto.serializer(), capabilities.toDto())
        return apiCallBody(json, itemId = itemId) { api.playbackInfo(itemId, capsJson) }
            .toDomain(itemId)
    }

    suspend fun progressSince(sinceMs: Long?): List<PlaybackProgress> =
        mapItemsSkippingBad(apiCallBody(json) { api.progress(sinceMs?.toIsoUtc()) }.items) {
            it.toDomain()
        }

    suspend fun putProgress(progress: PlaybackProgress) {
        apiCallBody(json, itemId = progress.itemId) {
            api.putProgress(
                itemId = progress.itemId,
                body = ProgressUpdateDto(
                    positionMs = progress.positionMs,
                    durationMs = progress.durationMs,
                    watched = progress.watched,
                    updatedAt = progress.updatedAtMs.toIsoUtc(),
                ),
            )
        }
    }

    suspend fun deleteProgress(itemId: String) {
        apiCallBody(json, itemId = itemId) { api.deleteProgress(itemId) }
    }

    suspend fun myList(): List<String> =
        apiCallBody(json) { api.myList() }.items.map { it.itemId }

    suspend fun addToMyList(itemId: String) {
        apiCallBody(json, itemId = itemId) { api.addToMyList(itemId) }
    }

    suspend fun removeFromMyList(itemId: String) {
        apiCallBody(json, itemId = itemId) { api.removeFromMyList(itemId) }
    }

    suspend fun search(query: String, limit: Int = MyflixApiService.SEARCH_LIMIT): List<SearchResult> =
        mapItemsSkippingBad(apiCallBody(json) { api.search(query, limit) }.items) { dto ->
            SearchResult(card = MediaCard(dto.item.toDomain()), matchedOn = dto.matchedOn)
        }

    private fun MediaType.wire(): String = when (this) {
        MediaType.MOVIE -> "movie"
        MediaType.SHOW -> "show"
    }

    private fun LibrarySort.wire(): String = when (this) {
        LibrarySort.RECENTLY_ADDED -> "recent"
        LibrarySort.ALPHABETICAL -> "title"
        LibrarySort.YEAR -> "year"
        LibrarySort.RATING -> "rating"
    }

    private fun WatchedFilter.wire(): String? = when (this) {
        WatchedFilter.ALL -> null
        WatchedFilter.WATCHED -> "true"
        WatchedFilter.UNWATCHED -> "false"
    }
}
