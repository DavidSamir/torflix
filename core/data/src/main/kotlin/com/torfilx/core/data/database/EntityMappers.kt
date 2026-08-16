package com.torfilx.core.data.database

import com.torfilx.core.model.Episode
import com.torfilx.core.model.Images
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaType
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.Season

/** Separator for the denormalised genre list; chosen because it cannot occur in a genre name. */
private const val GENRE_SEPARATOR = ""

fun MediaItem.toEntity(): LibraryItemEntity = LibraryItemEntity(
    id = id,
    type = type.name,
    title = title,
    sortTitle = sortTitle,
    year = year,
    runtimeMs = runtimeMs,
    communityRating = communityRating,
    ageRating = ageRating,
    overview = overview,
    genres = genres.joinToString(GENRE_SEPARATOR),
    posterUrl = images.poster,
    backdropUrl = images.backdrop,
    logoUrl = images.logo,
    thumbUrl = images.thumb,
    addedAtMs = addedAtMs,
    updatedAtMs = updatedAtMs,
    seasonCount = seasonCount,
    episodeCount = episodeCount,
)

fun LibraryItemEntity.toDomain(): MediaItem = MediaItem(
    id = id,
    type = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.MOVIE),
    title = title,
    sortTitle = sortTitle,
    year = year,
    runtimeMs = runtimeMs,
    communityRating = communityRating,
    ageRating = ageRating,
    overview = overview,
    genres = if (genres.isBlank()) emptyList() else genres.split(GENRE_SEPARATOR),
    images = Images(posterUrl, backdropUrl, logoUrl, thumbUrl),
    addedAtMs = addedAtMs,
    updatedAtMs = updatedAtMs,
    seasonCount = seasonCount,
    episodeCount = episodeCount,
)

fun Season.toEntity(): SeasonEntity = SeasonEntity(id, showId, number, name, posterUrl, episodeCount)

fun SeasonEntity.toDomain(): Season = Season(id, showId, number, name, posterUrl, episodeCount)

fun Episode.toEntity(): EpisodeEntity = EpisodeEntity(
    id = id,
    showId = showId,
    seasonId = seasonId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = title,
    overview = overview,
    runtimeMs = runtimeMs,
    thumbUrl = thumbUrl,
    airedAtMs = airedAtMs,
    updatedAtMs = updatedAtMs,
)

fun EpisodeEntity.toDomain(): Episode = Episode(
    id = id,
    showId = showId,
    seasonId = seasonId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = title,
    overview = overview,
    runtimeMs = runtimeMs,
    thumbUrl = thumbUrl,
    airedAtMs = airedAtMs,
    updatedAtMs = updatedAtMs,
)

fun PlaybackProgress.toEntity(
    syncState: SyncState = SyncState.PENDING,
    showId: String? = null,
): ProgressEntity = ProgressEntity(
    itemId = itemId,
    positionMs = positionMs,
    durationMs = durationMs,
    watched = watched,
    updatedAtMs = updatedAtMs,
    syncState = syncState.name,
    showId = showId,
)

fun ProgressEntity.toDomain(): PlaybackProgress = PlaybackProgress(
    itemId = itemId,
    positionMs = positionMs,
    durationMs = durationMs,
    watched = watched,
    updatedAtMs = updatedAtMs,
)
