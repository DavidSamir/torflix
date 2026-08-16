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
