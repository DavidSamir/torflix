package com.torfilx.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schemas are exported to `core/data/schemas` and committed, so every future migration is
 * reviewable and testable (plan.md §8.1). `fallbackToDestructiveMigration` is never enabled in
 * release builds — losing playback progress on upgrade is not acceptable.
 */
@Database(
    entities = [
        ProgressEntity::class,
        MyListEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TorfilxDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
    abstract fun myListDao(): MyListDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        const val NAME = "torfilx.db"
    }
}
