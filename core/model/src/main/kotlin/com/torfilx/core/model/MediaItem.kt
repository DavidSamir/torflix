package com.torfilx.core.model

/**
 * Artwork URLs from the catalogue.
 */
data class Images(
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val thumb: String? = null,
)

/**
 * A film in the catalogue.
 *
 * The app carries films only — no series, no seasons, no episodes — so there is no media *type* to
 * branch on anywhere in the codebase.
 *
 * Timestamps are epoch milliseconds (UTC): primitive on purpose, so the model stays free of
 * date-library churn and is trivially storable in Room.
 */
data class MediaItem(
    val id: String,
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
)
