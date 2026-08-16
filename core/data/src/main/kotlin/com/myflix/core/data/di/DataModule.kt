package com.myflix.core.data.di

import android.content.Context
import androidx.room.Room
import com.myflix.core.data.database.EpisodeDao
import com.myflix.core.data.database.LibraryDao
import com.myflix.core.data.database.MyListDao
import com.myflix.core.data.database.MyflixDatabase
import com.myflix.core.data.database.ProgressDao
import com.myflix.core.data.database.SearchHistoryDao
import com.myflix.core.data.database.SeasonDao
import com.myflix.core.data.database.SyncMarkerDao
import com.myflix.core.data.remote.MediaRemoteSource
import com.myflix.core.data.remote.RoutingMediaRemoteSource
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
    fun providesDatabase(@ApplicationContext context: Context): MyflixDatabase =
        Room.databaseBuilder(context, MyflixDatabase::class.java, MyflixDatabase.NAME)
            // No destructive fallback: losing watch progress on an upgrade is not acceptable, so a
            // missing migration must fail loudly in development instead of silently wiping data.
            .build()

    @Provides
    fun providesLibraryDao(database: MyflixDatabase): LibraryDao = database.libraryDao()

    @Provides
    fun providesSeasonDao(database: MyflixDatabase): SeasonDao = database.seasonDao()

    @Provides
    fun providesEpisodeDao(database: MyflixDatabase): EpisodeDao = database.episodeDao()

    @Provides
    fun providesProgressDao(database: MyflixDatabase): ProgressDao = database.progressDao()

    @Provides
    fun providesMyListDao(database: MyflixDatabase): MyListDao = database.myListDao()

    @Provides
    fun providesSearchHistoryDao(database: MyflixDatabase): SearchHistoryDao =
        database.searchHistoryDao()

    @Provides
    fun providesSyncMarkerDao(database: MyflixDatabase): SyncMarkerDao = database.syncMarkerDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteSourceModule {
    @Binds
    @Singleton
    abstract fun bindsMediaRemoteSource(impl: RoutingMediaRemoteSource): MediaRemoteSource
}
