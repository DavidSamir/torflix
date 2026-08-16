package com.torfilx.core.data.di

import android.content.Context
import androidx.room.Room
import com.torfilx.core.data.database.EpisodeDao
import com.torfilx.core.data.database.LibraryDao
import com.torfilx.core.data.database.MyListDao
import com.torfilx.core.data.database.TorfilxDatabase
import com.torfilx.core.data.database.ProgressDao
import com.torfilx.core.data.database.SearchHistoryDao
import com.torfilx.core.data.database.SeasonDao
import com.torfilx.core.data.database.SyncMarkerDao
import com.torfilx.core.data.remote.MediaRemoteSource
import com.torfilx.core.data.remote.RoutingMediaRemoteSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providesDatabase(@ApplicationContext context: Context): TorfilxDatabase =
        Room.databaseBuilder(context, TorfilxDatabase::class.java, TorfilxDatabase.NAME)
            // No destructive fallback: losing watch progress on an upgrade is not acceptable, so a
            // missing migration must fail loudly in development instead of silently wiping data.
            .build()

    @Provides
    fun providesLibraryDao(database: TorfilxDatabase): LibraryDao = database.libraryDao()

    @Provides
    fun providesSeasonDao(database: TorfilxDatabase): SeasonDao = database.seasonDao()

    @Provides
    fun providesEpisodeDao(database: TorfilxDatabase): EpisodeDao = database.episodeDao()

    @Provides
    fun providesProgressDao(database: TorfilxDatabase): ProgressDao = database.progressDao()

    @Provides
    fun providesMyListDao(database: TorfilxDatabase): MyListDao = database.myListDao()

    @Provides
    fun providesSearchHistoryDao(database: TorfilxDatabase): SearchHistoryDao =
        database.searchHistoryDao()

    @Provides
    fun providesSyncMarkerDao(database: TorfilxDatabase): SyncMarkerDao = database.syncMarkerDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteSourceModule {
    @Binds
    @Singleton
    abstract fun bindsMediaRemoteSource(impl: RoutingMediaRemoteSource): MediaRemoteSource
}
