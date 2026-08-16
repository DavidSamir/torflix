package com.torfilx.core.network.api

import com.torfilx.core.network.dto.EpisodeDto
import com.torfilx.core.network.dto.HomeDto
import com.torfilx.core.network.dto.ItemDetailsDto
import com.torfilx.core.network.dto.MediaItemDto
import com.torfilx.core.network.dto.MyListEntryDto
import com.torfilx.core.network.dto.PagedResponseDto
import com.torfilx.core.network.dto.PlaybackInfoDto
import com.torfilx.core.network.dto.ProgressDto
import com.torfilx.core.network.dto.ProgressUpdateDto
import com.torfilx.core.network.dto.SearchResultDto
import com.torfilx.core.network.dto.SeasonDto
import com.torfilx.core.network.dto.ServerInfoDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The HTTP contract from plan.md §11. Paths are relative — the absolute host comes from the
 * user-configured server URL, injected by `BaseUrlInterceptor`.
 *
 * Everything returns `Response<T>` so the caller can read the status code and `ETag` header rather
 * than relying on exceptions for control flow.
 */
interface TorfilxApiService {

    @GET("api/v1/server/info")
    suspend fun serverInfo(): Response<ServerInfoDto>

    @GET("api/v1/library/items")
    suspend fun libraryItems(
        @Query("type") type: String? = null,
        @Query("genre") genre: String? = null,
        @Query("sort") sort: String? = null,
        @Query("watched") watched: String? = null,
        @Query("since") sinceIso: String? = null,
        @Query("page") page: Int = 0,
        @Query("pageSize") pageSize: Int = PAGE_SIZE,
        @Header("If-None-Match") etag: String? = null,
    ): Response<PagedResponseDto<MediaItemDto>>

    @GET("api/v1/library/items/{id}")
    suspend fun itemDetails(@Path("id") id: String): Response<ItemDetailsDto>

    @GET("api/v1/shows/{id}/seasons")
    suspend fun seasons(@Path("id") showId: String): Response<PagedResponseDto<SeasonDto>>

    @GET("api/v1/shows/{id}/seasons/{number}/episodes")
    suspend fun episodes(
        @Path("id") showId: String,
        @Path("number") seasonNumber: Int,
    ): Response<PagedResponseDto<EpisodeDto>>

    @GET("api/v1/home")
    suspend fun home(@Header("If-None-Match") etag: String? = null): Response<HomeDto>

    @GET("api/v1/genres")
    suspend fun genres(): Response<List<String>>

    @GET("api/v1/items/{id}/playback-info")
    suspend fun playbackInfo(
        @Path("id") id: String,
        @Query("caps") capabilitiesJson: String,
    ): Response<PlaybackInfoDto>

    @GET("api/v1/progress")
    suspend fun progress(@Query("since") sinceIso: String? = null): Response<PagedResponseDto<ProgressDto>>

    @PUT("api/v1/progress/{id}")
    suspend fun putProgress(
        @Path("id") itemId: String,
        @Body body: ProgressUpdateDto,
    ): Response<Unit>

    @DELETE("api/v1/progress/{id}")
    suspend fun deleteProgress(@Path("id") itemId: String): Response<Unit>

    @GET("api/v1/my-list")
    suspend fun myList(): Response<PagedResponseDto<MyListEntryDto>>

    @PUT("api/v1/my-list/{id}")
    suspend fun addToMyList(@Path("id") itemId: String): Response<Unit>

    @DELETE("api/v1/my-list/{id}")
    suspend fun removeFromMyList(@Path("id") itemId: String): Response<Unit>

    @GET("api/v1/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("limit") limit: Int = SEARCH_LIMIT,
    ): Response<PagedResponseDto<SearchResultDto>>

    companion object {
        const val PAGE_SIZE = 60
        const val SEARCH_LIMIT = 40
    }
}
