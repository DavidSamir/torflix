package com.myflix.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for the API in plan.md §11.
 *
 * Every non-essential field is optional with a default: a server that omits or adds fields must
 * never crash the app, and a single malformed item must never take down a whole row (plan.md §10).
 */
@Serializable
data class PagedResponseDto<T>(
    val items: List<T> = emptyList(),
    val page: Int = 0,
    val pageSize: Int = 0,
    val total: Int = 0,
)

@Serializable
data class ServerInfoDto(
    val name: String = "Media server",
    val version: String = "unknown",
    val apiVersion: Int = 1,
    val features: List<String> = emptyList(),
)

@Serializable
data class ImagesDto(
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val thumb: String? = null,
)

@Serializable
data class MediaItemDto(
    val id: String,
    val type: String = "movie",
    val title: String = "",
    val sortTitle: String? = null,
    val year: Int? = null,
    val runtimeMs: Long? = null,
    val rating: Double? = null,
    val ageRating: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val images: ImagesDto = ImagesDto(),
    val addedAt: String? = null,
    val updatedAt: String? = null,
    val seasonCount: Int? = null,
    val episodeCount: Int? = null,
)

@Serializable
data class SeasonDto(
    val id: String,
    val showId: String? = null,
    val number: Int = 1,
    val name: String? = null,
    val poster: String? = null,
    val episodeCount: Int = 0,
)

@Serializable
data class EpisodeDto(
    val id: String,
    val showId: String? = null,
    val seasonId: String? = null,
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 0,
    val title: String? = null,
    val overview: String? = null,
    val runtimeMs: Long? = null,
    val thumb: String? = null,
    val airedAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ItemDetailsDto(
    val item: MediaItemDto,
    val seasons: List<SeasonDto> = emptyList(),
)

@Serializable
data class HomeRowDto(
    val id: String,
    val title: String = "",
    val type: String = "generic",
    val items: List<MediaItemDto> = emptyList(),
)

@Serializable
data class HomeDto(
    val rows: List<HomeRowDto> = emptyList(),
)

@Serializable
data class MediaSourceDto(
    val id: String,
    val kind: String = "direct",
    val url: String,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodecs: List<String> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    val fps: Float? = null,
    val hdr: String? = null,
    @SerialName("dvProfile") val dolbyVisionProfile: Int? = null,
    val bitrate: Long? = null,
)

@Serializable
data class SubtitleDto(
    val id: String,
    val lang: String? = null,
    val label: String? = null,
    val url: String? = null,
    val format: String? = null,
    val forced: Boolean = false,
    val default: Boolean = false,
    val embedded: Boolean = false,
)

@Serializable
data class AudioTrackDto(
    val id: String? = null,
    val index: Int = 0,
    val lang: String? = null,
    val label: String? = null,
    val channels: Int? = null,
    val codec: String? = null,
    val default: Boolean = false,
)

@Serializable
data class TimeRangeDto(val start: Long, val end: Long)

@Serializable
data class MarkersDto(
    val intro: TimeRangeDto? = null,
    val recap: TimeRangeDto? = null,
    val credits: TimeRangeDto? = null,
    val creditsStart: Long? = null,
)

@Serializable
data class SpriteSheetDto(
    val url: String,
    val interval: Long = 10_000,
    val cols: Int = 10,
    val rows: Int = 10,
    val thumbW: Int = 160,
    val thumbH: Int = 90,
)

@Serializable
data class ResumeDto(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watched: Boolean = false,
    val updatedAt: String? = null,
)

@Serializable
data class PlaybackInfoDto(
    val itemId: String? = null,
    val sources: List<MediaSourceDto> = emptyList(),
    val subtitles: List<SubtitleDto> = emptyList(),
    val audioTracks: List<AudioTrackDto> = emptyList(),
    val markers: MarkersDto? = null,
    val spriteSheet: SpriteSheetDto? = null,
    val resume: ResumeDto? = null,
    val durationMs: Long? = null,
)

@Serializable
data class ProgressDto(
    val itemId: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watched: Boolean = false,
    val updatedAt: String? = null,
)

@Serializable
data class ProgressUpdateDto(
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAt: String,
)

@Serializable
data class MyListEntryDto(
    val itemId: String,
    val addedAt: String? = null,
)

@Serializable
data class SearchResultDto(
    val item: MediaItemDto,
    val matchedOn: String? = null,
)

@Serializable
data class ErrorEnvelopeDto(
    val error: ErrorBodyDto? = null,
)

@Serializable
data class ErrorBodyDto(
    val code: String? = null,
    val message: String? = null,
)

// --- Capability report (sent to the server so it can choose direct play vs transcode) ------------

@Serializable
data class VideoDecoderDto(
    val mime: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
    val profiles: List<Int> = emptyList(),
)

@Serializable
data class CapabilitiesDto(
    val video: List<VideoDecoderDto> = emptyList(),
    val audio: List<String> = emptyList(),
    val passthrough: List<String> = emptyList(),
    val maxChannels: Int = 2,
    val hdr: List<String> = emptyList(),
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080,
    val refreshRates: List<Float> = emptyList(),
    val tunneling: Boolean = false,
)
