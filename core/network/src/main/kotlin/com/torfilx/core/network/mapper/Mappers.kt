package com.torfilx.core.network.mapper

import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.AudioTrackInfo
import com.torfilx.core.model.DeviceCapabilities
import com.torfilx.core.model.Episode
import com.torfilx.core.model.HdrType
import com.torfilx.core.model.HomeRow
import com.torfilx.core.model.HomeRowKind
import com.torfilx.core.model.Images
import com.torfilx.core.model.Markers
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaSource
import com.torfilx.core.model.MediaType
import com.torfilx.core.model.PlaybackInfo
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.Season
import com.torfilx.core.model.ServerFeature
import com.torfilx.core.model.ServerInfo
import com.torfilx.core.model.SourceKind
import com.torfilx.core.model.SpriteSheet
import com.torfilx.core.model.SubtitleFormat
import com.torfilx.core.model.SubtitleTrack
import com.torfilx.core.model.TimeRange
import com.torfilx.core.network.dto.AudioTrackDto
import com.torfilx.core.network.dto.CapabilitiesDto
import com.torfilx.core.network.dto.EpisodeDto
import com.torfilx.core.network.dto.HomeRowDto
import com.torfilx.core.network.dto.MarkersDto
import com.torfilx.core.network.dto.MediaItemDto
import com.torfilx.core.network.dto.MediaSourceDto
import com.torfilx.core.network.dto.PlaybackInfoDto
import com.torfilx.core.network.dto.ProgressDto
import com.torfilx.core.network.dto.SeasonDto
import com.torfilx.core.network.dto.ServerInfoDto
import com.torfilx.core.network.dto.SpriteSheetDto
import com.torfilx.core.network.dto.SubtitleDto
import com.torfilx.core.network.dto.VideoDecoderDto

internal const val MAPPER_TAG = "Mapper"

/**
 * DTO → domain mapping.
 *
 * Mapping is *lenient by design*: [mapItemsSkippingBad] drops an individual malformed entry and logs
 * it rather than failing the whole response, so one bad file on the server cannot blank out a row
 * (plan.md §10).
 */
fun MediaItemDto.toDomain(): MediaItem = MediaItem(
    id = id,
    type = if (type.equals("show", true) || type.equals("series", true)) MediaType.SHOW else MediaType.MOVIE,
    title = title.ifBlank { "Untitled" },
    sortTitle = sortTitle?.takeIf { it.isNotBlank() } ?: title.ifBlank { "Untitled" },
    year = year,
    runtimeMs = runtimeMs,
    communityRating = rating,
    ageRating = ageRating,
    overview = overview,
    genres = genres.filter { it.isNotBlank() },
    images = Images(
        poster = images.poster,
        backdrop = images.backdrop,
        logo = images.logo,
        thumb = images.thumb,
    ),
    addedAtMs = addedAt.toEpochMillisOrNull(),
    updatedAtMs = updatedAt.toEpochMillisOrNull() ?: 0L,
    seasonCount = seasonCount,
    episodeCount = episodeCount,
)

fun SeasonDto.toDomain(fallbackShowId: String): Season = Season(
    id = id,
    showId = showId ?: fallbackShowId,
    number = number,
    name = name,
    posterUrl = poster,
    episodeCount = episodeCount,
)

fun EpisodeDto.toDomain(fallbackShowId: String, fallbackSeasonId: String): Episode = Episode(
    id = id,
    showId = showId ?: fallbackShowId,
    seasonId = seasonId ?: fallbackSeasonId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = title,
    overview = overview,
    runtimeMs = runtimeMs,
    thumbUrl = thumb,
    airedAtMs = airedAt.toEpochMillisOrNull(),
    updatedAtMs = updatedAt.toEpochMillisOrNull() ?: 0L,
)

fun HomeRowDto.toDomain(): HomeRow = HomeRow(
    id = id,
    title = title,
    kind = when (type.lowercase()) {
        "continue", "continue-watching" -> HomeRowKind.CONTINUE_WATCHING
        "list", "my-list" -> HomeRowKind.MY_LIST
        "recent", "recently-added" -> HomeRowKind.RECENTLY_ADDED
        "genre" -> HomeRowKind.GENRE
        "recommended" -> HomeRowKind.RECOMMENDED
        else -> HomeRowKind.GENERIC
    },
    items = mapItemsSkippingBad(items) { MediaCard(it.toDomain()) },
)

fun ServerInfoDto.toDomain(): ServerInfo = ServerInfo(
    name = name,
    version = version,
    apiVersion = apiVersion,
    features = features.map { feature ->
        when (feature.lowercase()) {
            "transcode" -> ServerFeature.TRANSCODE
            "sprites" -> ServerFeature.SPRITES
            "markers" -> ServerFeature.MARKERS
            "search-people", "people" -> ServerFeature.SEARCH_PEOPLE
            else -> ServerFeature.UNKNOWN
        }
    }.toSet(),
)

fun ProgressDto.toDomain(): PlaybackProgress = PlaybackProgress(
    itemId = itemId,
    positionMs = positionMs.coerceAtLeast(0),
    durationMs = durationMs.coerceAtLeast(0),
    watched = watched,
    updatedAtMs = updatedAt.toEpochMillisOrNull() ?: 0L,
)

fun MediaSourceDto.toDomain(): MediaSource = MediaSource(
    id = id,
    kind = if (kind.equals("hls", true)) SourceKind.HLS else SourceKind.DIRECT,
    url = url,
    container = container,
    videoCodec = videoCodec,
    audioCodecs = audioCodecs,
    width = width,
    height = height,
    frameRate = fps,
    hdr = hdr.toHdrType(),
    dolbyVisionProfile = dolbyVisionProfile,
    bitrate = bitrate,
)

fun SubtitleDto.toDomain(): SubtitleTrack = SubtitleTrack(
    id = id,
    language = lang,
    label = label,
    url = url,
    format = when (format?.lowercase()) {
        "vtt", "webvtt" -> SubtitleFormat.VTT
        "srt", "subrip" -> SubtitleFormat.SRT
        "ass", "ssa" -> SubtitleFormat.ASS
        "pgs", "pgssub" -> SubtitleFormat.PGS
        "vobsub", "dvdsub" -> SubtitleFormat.DVD_SUB
        null -> if (url != null) SubtitleFormat.VTT else SubtitleFormat.UNKNOWN
        else -> SubtitleFormat.UNKNOWN
    },
    isForced = forced,
    isDefault = default,
    isEmbedded = embedded || url == null,
)

fun AudioTrackDto.toDomain(): AudioTrackInfo = AudioTrackInfo(
    id = id ?: "audio-$index",
    index = index,
    language = lang,
    label = label,
    channels = channels,
    codec = codec,
    isDefault = default,
)

fun MarkersDto?.toDomain(): Markers {
    if (this == null) return Markers()
    return Markers(
        intro = intro?.let { TimeRange(it.start, it.end) },
        recap = recap?.let { TimeRange(it.start, it.end) },
        creditsStartMs = creditsStart ?: credits?.start,
    )
}

fun SpriteSheetDto.toDomain(): SpriteSheet = SpriteSheet(
    url = url,
    intervalMs = interval,
    columns = cols.coerceAtLeast(1),
    rows = rows.coerceAtLeast(1),
    thumbWidth = thumbW.coerceAtLeast(1),
    thumbHeight = thumbH.coerceAtLeast(1),
)

fun PlaybackInfoDto.toDomain(requestedItemId: String): PlaybackInfo = PlaybackInfo(
    itemId = itemId ?: requestedItemId,
    sources = mapItemsSkippingBad(sources) { it.toDomain() },
    subtitles = mapItemsSkippingBad(subtitles) { it.toDomain() },
    audioTracks = mapItemsSkippingBad(audioTracks) { it.toDomain() },
    markers = markers.toDomain(),
    spriteSheet = spriteSheet?.toDomain(),
    resume = resume?.let {
        PlaybackProgress(
            itemId = itemId ?: requestedItemId,
            positionMs = it.positionMs,
            durationMs = it.durationMs,
            watched = it.watched,
            updatedAtMs = it.updatedAt.toEpochMillisOrNull() ?: 0L,
        )
    },
    durationMs = durationMs,
)

fun DeviceCapabilities.toDto(): CapabilitiesDto = CapabilitiesDto(
    video = videoDecoders.map {
        VideoDecoderDto(
            mime = it.mimeType,
            maxWidth = it.maxWidth,
            maxHeight = it.maxHeight,
            maxFps = it.maxFrameRate,
            profiles = it.profiles.toList(),
        )
    },
    audio = audioCodecs.toList(),
    passthrough = passthroughCodecs.toList(),
    maxChannels = maxAudioChannels,
    hdr = displayHdrTypes.map { it.name },
    maxWidth = maxDisplayWidth,
    maxHeight = maxDisplayHeight,
    refreshRates = supportedRefreshRates,
    tunneling = supportsTunneledPlayback,
)

private fun String?.toHdrType(): HdrType = when (this?.lowercase()) {
    "hdr10" -> HdrType.HDR10
    "hdr10+", "hdr10plus" -> HdrType.HDR10_PLUS
    "dv", "dolbyvision", "dolby_vision" -> HdrType.DOLBY_VISION
    "hlg" -> HdrType.HLG
    else -> HdrType.NONE
}

/**
 * Parses ISO-8601 timestamps.
 *
 * Accepts `2024-05-01T12:00:00Z`, offsets, and fractional seconds. Anything unparseable yields null
 * rather than throwing — a bad timestamp must not lose an item.
 */
fun String?.toEpochMillisOrNull(): Long? {
    if (this.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(this).toEpochMilli() }
        .recoverCatching { java.time.OffsetDateTime.parse(this).toInstant().toEpochMilli() }
        .recoverCatching {
            java.time.LocalDateTime.parse(this).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }
        .getOrNull()
}

/** Formats epoch millis back to the ISO-8601 UTC form the API expects. */
fun Long.toIsoUtc(): String = java.time.Instant.ofEpochMilli(this).toString()

/** Maps a list, dropping (and logging) entries whose mapping throws. */
fun <T, R> mapItemsSkippingBad(items: List<T>, transform: (T) -> R): List<R> =
    items.mapNotNull { item ->
        try {
            transform(item)
        } catch (error: IllegalArgumentException) {
            TorfilxLog.w(MAPPER_TAG, "Skipping malformed item: ${error.message}")
            null
        } catch (error: NullPointerException) {
            TorfilxLog.w(MAPPER_TAG, "Skipping item with missing required field: ${error.message}")
            null
        }
    }
