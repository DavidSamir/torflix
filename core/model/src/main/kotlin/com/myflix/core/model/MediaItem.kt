package com.myflix.core.model

/** Movie or show. Episodes are modelled separately ([Episode]) because they are never browsed alone. */
enum class MediaType { MOVIE, SHOW }

/**
 * Artwork URLs as returned by the server. The app appends a `?w=` query when requesting a size
 * (plan.md §11.4) so it never downloads a 4K backdrop to draw a 150 dp poster.
 */
data class Images(
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val thumb: String? = null,
)

/**
 * A browsable library entry.
 *
 * Timestamps are epoch milliseconds (UTC) — deliberately primitive so the model stays free of
 * date-library churn and is trivially storable in Room and comparable during sync conflict
 * resolution (plan.md §7.5).
 */
data class MediaItem(
    val id: String,
    val type: MediaType,
    val title: String,
    val sortTitle: String = title,
    val year: Int? = null,
    val runtimeMs: Long? = null,
    val communityRating: Double? = null,
    val ageRating: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val images: Images = Images(),
    val addedAtMs: Long? = null,
    val updatedAtMs: Long = 0L,
    /** Shows only. */
    val seasonCount: Int? = null,
    /** Shows only. */
    val episodeCount: Int? = null,
    val officialRating: String? = null,
) {
    val isShow: Boolean get() = type == MediaType.SHOW
    val isMovie: Boolean get() = type == MediaType.MOVIE
}

/** A show season. Season 0 is "Specials" and is always ordered last (plan.md §6.4). */
data class Season(
    val id: String,
    val showId: String,
    val number: Int,
    val name: String? = null,
    val posterUrl: String? = null,
    val episodeCount: Int = 0,
) {
    val isSpecials: Boolean get() = number == 0
}

data class Episode(
    val id: String,
    val showId: String,
    val seasonId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val overview: String? = null,
    val runtimeMs: Long? = null,
    val thumbUrl: String? = null,
    val airedAtMs: Long? = null,
    val updatedAtMs: Long = 0L,
)

/** A show with everything the details screen needs in one shot. */
data class ShowDetails(
    val show: MediaItem,
    val seasons: List<Season>,
    /** Episodes of the seasons that have been loaded so far, keyed by season id. */
    val episodesBySeason: Map<String, List<Episode>> = emptyMap(),
) {
    /** Aired order across the whole show: seasons ascending, Specials (season 0) last. */
    val episodesInAiredOrder: List<Episode>
        get() = seasons
            .sortedWith(compareBy({ it.isSpecials }, { it.number }))
            .flatMap { season ->
                episodesBySeason[season.id].orEmpty().sortedBy { it.episodeNumber }
            }
}
