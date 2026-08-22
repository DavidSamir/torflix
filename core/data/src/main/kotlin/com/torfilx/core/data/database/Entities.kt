package com.torfilx.core.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local state only.
 *
 * The catalogue itself ships with the app, so nothing about the films is stored here — only what
 * this device knows: where playback got to, what was saved, and what was searched for.
 */
@Entity(
    tableName = "progress",
    indices = [Index("updatedAtMs")],
)
data class ProgressEntity(
    @PrimaryKey val itemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAtMs: Long,
)

@Entity(tableName = "my_list", indices = [Index("addedAtMs")])
data class MyListEntity(
    @PrimaryKey val itemId: String,
    val addedAtMs: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAtMs: Long,
)

/**
 * Lifetime record of what this device has shared for one title.
 *
 * Keyed by info hash, not by catalogue id: a contribution outlives the thing that produced it. The
 * film can be evicted from disk, dropped from the catalogue or renamed, and what was shared still
 * happened — so the title is denormalised here rather than joined at read time.
 */
@Entity(tableName = "contribution", indices = [Index("lastActiveAtMs")])
data class ContributionEntity(
    @PrimaryKey val infoHash: String,
    val title: String,
    val uploadedBytes: Long,
    val downloadedBytes: Long,
    val sizeBytes: Long,
    val firstSharedAtMs: Long,
    val lastActiveAtMs: Long,
    /** False once the data has been evicted — the record stays, the file is gone. */
    val stillOnDisk: Boolean,
)

/**
 * One calendar day of totals, for the chart.
 *
 * A rollup rather than a sample log: bounded at ninety rows, which is all the chart reads and is
 * trivial to query on a slow device. Keyed by epoch day so ordering and pruning are integer work.
 */
@Entity(tableName = "contribution_day")
data class ContributionDayEntity(
    @PrimaryKey val epochDay: Long,
    val uploadedBytes: Long,
    val downloadedBytes: Long,
)
