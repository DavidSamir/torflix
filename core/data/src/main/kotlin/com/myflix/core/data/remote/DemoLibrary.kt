package com.myflix.core.data.remote

import com.myflix.core.model.Episode
import com.myflix.core.model.HdrType
import com.myflix.core.model.Images
import com.myflix.core.model.Markers
import com.myflix.core.model.MediaItem
import com.myflix.core.model.MediaSource
import com.myflix.core.model.MediaType
import com.myflix.core.model.PlaybackInfo
import com.myflix.core.model.Season
import com.myflix.core.model.SourceKind
import com.myflix.core.model.SpriteSheet
import com.myflix.core.model.SubtitleFormat
import com.myflix.core.model.SubtitleTrack
import com.myflix.core.model.TimeRange
import kotlin.math.abs
import kotlin.random.Random

/**
 * A deterministic, realistic demo catalogue used to build and test the whole UI before the server
 * exists (plan.md §0).
 *
 * It is deliberately awkward: very long titles, missing artwork, missing overviews, a Hebrew (RTL)
 * title, one-season and eight-season shows, Specials, 4K HDR and DV-profile-7 sources, and a show
 * with no episode metadata. If the UI survives this it will survive a real library.
 */
object DemoLibrary {

    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE

    /** Fixed epoch so demo data is stable across runs (2026-01-01T00:00:00Z). */
    private const val EPOCH = 1_767_225_600_000L

    private val genres = listOf(
        "Action", "Sci-Fi", "Drama", "Comedy", "Thriller", "Documentary", "Animation", "Horror",
    )

    private val movieTitles = listOf(
        "Neon Harbour", "The Quiet Ascent", "Copper Sky", "Voyager's Return", "Glass Cathedral",
        "Salt and Iron", "The Longest Night", "Paper Kingdoms", "Silent Orbit", "Winter Signal",
        "Dust and Ashes", "The Cartographer", "Hollow Point", "Northern Lights Over an Empty Motorway",
        "Ember", "Riverbend", "The Ninth Gate of an Unremarkable Suburban House", "Static",
        "Cold Harvest", "The Last Broadcast", "Marrow", "Chrome Valley", "Pale Fire",
        "אור אחרון", "Undertow", "The Weight of Water", "Sparrow", "Blackout Protocol",
        "Meridian", "The Understudy", "Fault Lines", "Nightjar", "Ironwood", "Halcyon",
        "The Second Sun", "Gravity's Edge", "Lantern", "Foxglove", "The Recital", "Tidewater",
    )

    private val showTitles = listOf(
        "Deep Field", "The Bureau of Small Miracles", "Cold Open", "Harbour Lights",
        "The Archivists", "Nightwatch", "Signal Lost", "Quarry",
    )

    /** All movies and shows in the demo library. */
    val items: List<MediaItem> by lazy { buildItems() }

    val seasonsByShow: Map<String, List<Season>> by lazy { buildSeasons() }

    val episodesBySeason: Map<String, List<Episode>> by lazy { buildEpisodes() }

    val allEpisodes: List<Episode> by lazy { episodesBySeason.values.flatten() }

    fun item(id: String): MediaItem? = items.firstOrNull { it.id == id }

    fun episode(id: String): Episode? = allEpisodes.firstOrNull { it.id == id }

    fun genresInLibrary(): List<String> = items.flatMap { it.genres }.distinct().sorted()

    private fun buildItems(): List<MediaItem> {
        val random = Random(seed = 42)
        val movies = movieTitles.mapIndexed { index, title ->
            val hasArtwork = index % 9 != 3 // every ninth movie has no poster: exercises the fallback
            val hasOverview = index % 11 != 5
            MediaItem(
                id = "movie-$index",
                type = MediaType.MOVIE,
                title = title,
                sortTitle = title.removePrefix("The ").trim(),
                year = 1978 + random.nextInt(48),
                runtimeMs = (78 + random.nextInt(85)) * MINUTE,
                communityRating = (55 + random.nextInt(45)) / 10.0,
                ageRating = listOf("G", "PG", "PG-13", "16+", "18+")[index % 5],
                overview = if (hasOverview) overviewFor(title, index) else null,
                genres = listOf(genres[index % genres.size], genres[(index * 3 + 1) % genres.size])
                    .distinct(),
                images = if (hasArtwork) {
                    Images(
                        poster = demoUri("poster", "movie-$index", title),
                        backdrop = demoUri("backdrop", "movie-$index", title),
                        logo = null,
                        thumb = demoUri("thumb", "movie-$index", title),
                    )
                } else {
                    Images()
                },
                addedAtMs = EPOCH - index * DAY,
                updatedAtMs = EPOCH - index * DAY,
            )
        }

        val shows = showTitles.mapIndexed { index, title ->
            val seasonCount = SHOW_SEASON_COUNTS[index % SHOW_SEASON_COUNTS.size]
            MediaItem(
                id = "show-$index",
                type = MediaType.SHOW,
                title = title,
                sortTitle = title.removePrefix("The ").trim(),
                year = 2001 + index * 2,
                runtimeMs = null,
                communityRating = (60 + index * 4) / 10.0,
                ageRating = listOf("PG", "PG-13", "16+", "18+")[index % 4],
                overview = overviewFor(title, index + 100),
                genres = listOf(genres[(index + 2) % genres.size], "Drama").distinct(),
                images = Images(
                    poster = demoUri("poster", "show-$index", title),
                    backdrop = demoUri("backdrop", "show-$index", title),
                    thumb = demoUri("thumb", "show-$index", title),
                ),
                addedAtMs = EPOCH - (index + 3) * DAY,
                updatedAtMs = EPOCH - (index + 3) * DAY,
                seasonCount = seasonCount,
                episodeCount = seasonCount * EPISODES_PER_SEASON,
            )
        }

        return movies + shows
    }

    private fun buildSeasons(): Map<String, List<Season>> =
        items.filter { it.isShow }.associate { show ->
            val index = show.id.substringAfter("show-").toInt()
            val count = SHOW_SEASON_COUNTS[index % SHOW_SEASON_COUNTS.size]
            val regular = (1..count).map { number ->
                Season(
                    id = "${show.id}-s$number",
                    showId = show.id,
                    number = number,
                    name = null,
                    posterUrl = demoUri("poster", "${show.id}-s$number", "${show.title} S$number"),
                    episodeCount = EPISODES_PER_SEASON,
                )
            }
            // Every third show also has Specials, which must sort last.
            val specials = if (index % 3 == 0) {
                listOf(
                    Season(
                        id = "${show.id}-s0",
                        showId = show.id,
                        number = 0,
                        name = "Specials",
                        posterUrl = null,
                        episodeCount = 2,
                    ),
                )
            } else {
                emptyList()
            }
            show.id to (regular + specials)
        }

    private fun buildEpisodes(): Map<String, List<Episode>> = buildMap {
        seasonsByShow.forEach { (showId, seasons) ->
            val showIndex = showId.substringAfter("show-").toInt()
            seasons.forEach { season ->
                val count = if (season.isSpecials) 2 else EPISODES_PER_SEASON
                val episodes = (1..count).map { number ->
                    // One show ships with no episode titles at all: the UI must fall back to
                    // "Episode N" (plan.md §6.4).
                    val untitledShow = showIndex == 4
                    Episode(
                        id = "${season.id}e$number",
                        showId = showId,
                        seasonId = season.id,
                        seasonNumber = season.number,
                        episodeNumber = number,
                        title = if (untitledShow) null else episodeTitle(showIndex, season.number, number),
                        overview = if (number % 7 == 3) null else episodeOverview(showIndex, number),
                        runtimeMs = (24 + (showIndex + number) % 30) * MINUTE,
                        thumbUrl = if (number % 5 == 2) {
                            null // missing thumbnails must fall back to the show backdrop
                        } else {
                            demoUri("thumb", "${season.id}e$number", "S${season.number}E$number")
                        },
                        airedAtMs = EPOCH - (showIndex * 400L + season.number * 30L + number) * DAY,
                        updatedAtMs = EPOCH,
                    )
                }
                put(season.id, episodes)
            }
        }
    }

    /**
     * Playback sources for a demo item. Includes a 4K HDR direct source, an HLS fallback and — for
     * one item — an undecodable Dolby Vision profile 7 source, so the selector's fallback path is
     * exercised in the running app and not only in unit tests.
     */
    fun playbackInfo(itemId: String, durationMs: Long): PlaybackInfo {
        val hash = abs(itemId.hashCode())
        val is4k = hash % 3 == 0
        val sources = buildList {
            if (hash % 17 == 0) {
                add(
                    MediaSource(
                        id = "$itemId-dv7",
                        kind = SourceKind.DIRECT,
                        url = "demo://media/$itemId/dv7",
                        container = "mkv",
                        videoCodec = "hevc",
                        audioCodecs = listOf("truehd"),
                        width = 3840,
                        height = 2160,
                        frameRate = 23.976f,
                        hdr = HdrType.DOLBY_VISION,
                        dolbyVisionProfile = 7,
                        bitrate = 60_000_000,
                    ),
                )
            }
            add(
                MediaSource(
                    id = "$itemId-direct",
                    kind = SourceKind.DIRECT,
                    url = "demo://media/$itemId/direct",
                    container = "mkv",
                    videoCodec = if (is4k) "hevc" else "h264",
                    audioCodecs = listOf(if (is4k) "eac3" else "aac"),
                    width = if (is4k) 3840 else 1920,
                    height = if (is4k) 2160 else 1080,
                    frameRate = if (hash % 2 == 0) 23.976f else 25f,
                    hdr = if (is4k) HdrType.HDR10 else HdrType.NONE,
                    bitrate = if (is4k) 38_000_000 else 9_000_000,
                ),
            )
            add(
                MediaSource(
                    id = "$itemId-hls",
                    kind = SourceKind.HLS,
                    url = "demo://media/$itemId/hls",
                    container = "fmp4",
                    videoCodec = "h264",
                    audioCodecs = listOf("aac"),
                    width = 1920,
                    height = 1080,
                    frameRate = 25f,
                    bitrate = 8_000_000,
                ),
            )
        }

        return PlaybackInfo(
            itemId = itemId,
            sources = sources,
            subtitles = listOf(
                SubtitleTrack("$itemId-sub-en", "en", "English", "demo://subs/$itemId/en", SubtitleFormat.VTT),
                SubtitleTrack("$itemId-sub-he", "he", "עברית", "demo://subs/$itemId/he", SubtitleFormat.VTT),
                SubtitleTrack(
                    id = "$itemId-sub-pgs",
                    language = "en",
                    label = "English (image)",
                    url = null,
                    format = SubtitleFormat.PGS,
                    isEmbedded = true,
                ),
                SubtitleTrack(
                    id = "$itemId-sub-forced",
                    language = "en",
                    label = "English (forced)",
                    url = "demo://subs/$itemId/forced",
                    format = SubtitleFormat.VTT,
                    isForced = true,
                ),
            ),
            audioTracks = listOf(
                com.myflix.core.model.AudioTrackInfo("$itemId-a0", 0, "en", "English 5.1", 6, "eac3", true),
                com.myflix.core.model.AudioTrackInfo("$itemId-a1", 1, "he", "עברית", 2, "aac"),
            ),
            markers = Markers(
                intro = if (hash % 4 != 0) TimeRange(45_000, 105_000) else null,
                recap = null,
                creditsStartMs = (durationMs - 90_000).coerceAtLeast(0),
            ),
            spriteSheet = SpriteSheet(
                url = "demo://sprites/$itemId",
                intervalMs = 10_000,
                columns = 10,
                rows = 10,
                thumbWidth = 160,
                thumbHeight = 90,
            ),
            durationMs = durationMs,
        )
    }

    private fun overviewFor(title: String, seed: Int): String {
        val sentences = listOf(
            "A quiet story about people who refuse to leave.",
            "Fifteen years after the accident, the town still keeps its lights on.",
            "An engineer, a courier and a stolen recording set out across the border.",
            "Nothing here is as ordinary as the paperwork suggests.",
            "The signal repeats every nine hours, and someone is finally listening.",
        )
        return "$title. " + (0..(seed % 3)).joinToString(" ") { sentences[(seed + it) % sentences.size] }
    }

    private fun episodeTitle(showIndex: Int, season: Int, number: Int): String {
        val words = listOf(
            "Landfall", "The Inventory", "Blue Hour", "Terminal", "Homework", "The Second Interview",
            "Rain Check", "Nightingale", "Overtime", "The Long Way Round", "Cutover", "Fieldwork",
            "An Unusually Long Episode Title That The Layout Has To Handle Gracefully",
        )
        return words[(showIndex * 7 + season * 3 + number) % words.size]
    }

    private fun episodeOverview(showIndex: Int, number: Int): String =
        "Episode $number picks up where the last one left off, and the team finally reads the file."

    private fun demoUri(kind: String, id: String, label: String): String =
        "demo://$kind/$id?label=${label.take(40).replace(" ", "%20")}"

    private const val EPISODES_PER_SEASON = 10
    private val SHOW_SEASON_COUNTS = intArrayOf(1, 3, 8, 2, 5, 4, 6, 2)
}
