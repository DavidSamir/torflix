package com.myflix.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schemas are exported to `core/data/schemas` and committed, so every future migration is
 * reviewable and testable (plan.md §8.1). `fallbackToDestructiveMigration` is never enabled in
 * release builds — losing playback progress on upgrade is not acceptable.
 */
@Database(
    entities = [
        LibraryItemEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        ProgressEntity::class,
        MyListEntity::class,
        SearchHistoryEntity::class,
        SyncMarkerEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MyflixDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun seasonDao(): SeasonDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun progressDao(): ProgressDao
    abstract fun myListDao(): MyListDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun syncMarkerDao(): SyncMarkerDao

    companion object {
        const val NAME = "myflix.db"
    }
}
