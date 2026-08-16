package com.myflix.core.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local mirror of the library. Everything the browse screens need must be readable without the
 * server: when the PC is asleep the app still shows the last known library (plan.md §3, §10).
 */
@Entity(
    tableName = "library_items",
    indices = [Index("type"), Index("sortTitle"), Index("addedAtMs"), Index("updatedAtMs")],
)
data class LibraryItemEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val sortTitle: String,
    val year: Int?,
    val runtimeMs: Long?,
    val communityRating: Double?,
    val ageRating: String?,
    val overview: String?,
    /** Genres joined with `` — a separator that cannot appear in a genre name. */
    val genres: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val thumbUrl: String?,
    val addedAtMs: Long?,
    val updatedAtMs: Long,
    val seasonCount: Int?,
    val episodeCount: Int?,
)

@Entity(
    tableName = "seasons",
    indices = [Index("showId")],
)
data class SeasonEntity(
    @PrimaryKey val id: String,
    val showId: String,
    val number: Int,
    val name: String?,
    val posterUrl: String?,
    val episodeCount: Int,
)

@Entity(
    tableName = "episodes",
    indices = [Index("showId"), Index("seasonId"), Index("seasonNumber", "episodeNumber")],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val showId: String,
    val seasonId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String?,
    val overview: String?,
    val runtimeMs: Long?,
    val thumbUrl: String?,
    val airedAtMs: Long?,
    val updatedAtMs: Long,
)

/** Sync state for locally-originated writes (the outbox pattern, plan.md §8.2). */
enum class SyncState { SYNCED, PENDING, FAILED }

@Entity(
    tableName = "progress",
    indices = [Index("updatedAtMs"), Index("syncState")],
)
data class ProgressEntity(
    @PrimaryKey val itemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAtMs: Long,
    val syncState: String = SyncState.PENDING.name,
    /** For episodes: which show they belong to, so Continue Watching can roll up to the show. */
    val showId: String? = null,
)

@Entity(tableName = "my_list", indices = [Index("addedAtMs"), Index("syncState")])
data class MyListEntity(
    @PrimaryKey val itemId: String,
    val addedAtMs: Long,
    val syncState: String = SyncState.PENDING.name,
    /** A locally removed entry is kept as a tombstone until the deletion is pushed to the server. */
    val deleted: Boolean = false,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAtMs: Long,
)

/** ETags and cursors, keyed by an opaque string (`home`, `library:movie`, `progress`…). */
@Entity(tableName = "sync_markers")
data class SyncMarkerEntity(
    @PrimaryKey val key: String,
    val etag: String? = null,
    val lastSyncedAtMs: Long = 0L,
)
