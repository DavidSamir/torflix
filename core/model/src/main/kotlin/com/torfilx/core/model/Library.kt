package com.torfilx.core.model

/** A card as displayed in a row or grid: the film plus whatever local state decorates it. */
data class MediaCard(
    val item: MediaItem,
    val progress: PlaybackProgress? = null,
    val inMyList: Boolean = false,
) {
    val playableId: String get() = item.id
}

/** Home rows are ordered by the server, except the two the app owns locally (plan.md §11.3). */
enum class HomeRowKind { CONTINUE_WATCHING, MY_LIST, RECENTLY_ADDED, GENRE, RECOMMENDED, GENERIC }

data class HomeRow(
    val id: String,
    val title: String,
    val kind: HomeRowKind = HomeRowKind.GENERIC,
    val items: List<MediaCard> = emptyList(),
)

/** The featured items at the top of Home. */
data class HeroItem(
    val card: MediaCard,
    val action: PlayAction,
)

enum class LibrarySort {
    RECENTLY_ADDED,
    ALPHABETICAL,
    YEAR,
    RATING,
    ;

    companion object {
        val DEFAULT = RECENTLY_ADDED
    }
}

enum class WatchedFilter { ALL, WATCHED, UNWATCHED }

data class LibraryQuery(
    val genre: String? = null,
    val sort: LibrarySort = LibrarySort.DEFAULT,
    val watched: WatchedFilter = WatchedFilter.ALL,
)

data class SearchResult(
    val card: MediaCard,
    val matchedOn: String? = null,
)

