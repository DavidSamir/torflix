package com.myflix.core.testing

import com.myflix.core.model.Episode
import com.myflix.core.model.HomeRow
import com.myflix.core.model.HomeRowKind
import com.myflix.core.model.Images
import com.myflix.core.model.MediaCard
import com.myflix.core.model.MediaItem
import com.myflix.core.model.MediaType
import com.myflix.core.model.PlaybackProgress
import com.myflix.core.model.Season
import com.myflix.core.model.ShowDetails

/** Deterministic fixtures shared by unit and instrumented tests. */
object Fixtures {

    const val MINUTE_MS = 60_000L
    const val NOW_MS = 1_767_225_600_000L // 2026-01-01T00:00:00Z

    fun movie(
        id: String = "movie-1",
        title: String = "Neon Harbour",
        runtimeMs: Long = 118 * MINUTE_MS,
        withArtwork: Boolean = true,
    ) = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = title,
        sortTitle = title,
        year = 2024,
        runtimeMs = runtimeMs,
        communityRating = 8.4,
        ageRating = "16+",
        overview = "A quiet story about people who refuse to leave.",
        genres = listOf("Drama", "Sci-Fi"),
        images = if (withArtwork) {
            Images(poster = "http://server/p/$id", backdrop = "http://server/b/$id")
        } else {
            Images()
        },
        addedAtMs = NOW_MS,
        updatedAtMs = NOW_MS,
    )

    fun show(
        id: String = "show-1",
        title: String = "Deep Field",
        seasonCount: Int = 2,
    ) = MediaItem(
        id = id,
        type = MediaType.SHOW,
        title = title,
        sortTitle = title,
        year = 2019,
        communityRating = 8.9,
        ageRating = "16+",
        overview = "The signal repeats every nine hours.",
        genres = listOf("Sci-Fi"),
        images = Images(poster = "http://server/p/$id", backdrop = "http://server/b/$id"),
        addedAtMs = NOW_MS,
        updatedAtMs = NOW_MS,
        seasonCount = seasonCount,
        episodeCount = seasonCount * 3,
    )

    fun season(showId: String = "show-1", number: Int = 1) = Season(
        id = "$showId-s$number",
        showId = showId,
        number = number,
        episodeCount = 3,
    )

    fun episode(
        showId: String = "show-1",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        runtimeMs: Long = 45 * MINUTE_MS,
    ) = Episode(
        id = "$showId-s${seasonNumber}e$episodeNumber",
        showId = showId,
        seasonId = "$showId-s$seasonNumber",
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        title = "Episode $episodeNumber",
        overview = "Something happens.",
        runtimeMs = runtimeMs,
        updatedAtMs = NOW_MS,
    )

    fun showDetails(showId: String = "show-1", seasons: Int = 2, episodesPerSeason: Int = 3): ShowDetails {
        val seasonList = (1..seasons).map { season(showId, it) }
        val episodes = seasonList.associate { s ->
            s.id to (1..episodesPerSeason).map { episode(showId, s.number, it) }
        }
        return ShowDetails(show(showId, seasonCount = seasons), seasonList, episodes)
    }

    fun progress(
        itemId: String = "movie-1",
        positionMs: Long = 40 * MINUTE_MS,
        durationMs: Long = 118 * MINUTE_MS,
        watched: Boolean = false,
        updatedAtMs: Long = NOW_MS,
    ) = PlaybackProgress(itemId, positionMs, durationMs, watched, updatedAtMs)

    fun card(item: MediaItem = movie(), progress: PlaybackProgress? = null, inMyList: Boolean = false) =
        MediaCard(item = item, progress = progress, inMyList = inMyList)

    fun row(
        id: String = "row-1",
        title: String = "Recently Added",
        kind: HomeRowKind = HomeRowKind.RECENTLY_ADDED,
        size: Int = 5,
    ) = HomeRow(
        id = id,
        title = title,
        kind = kind,
        items = (1..size).map { card(movie(id = "movie-$it", title = "Movie $it")) },
    )
}
