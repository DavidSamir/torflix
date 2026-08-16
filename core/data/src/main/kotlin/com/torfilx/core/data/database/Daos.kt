package com.torfilx.core.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Upsert
    suspend fun upsertAll(items: List<LibraryItemEntity>)

    @Query("SELECT * FROM library_items WHERE id = :id")
    fun observeItem(id: String): Flow<LibraryItemEntity?>

    @Query("SELECT * FROM library_items WHERE id = :id")
    suspend fun getItem(id: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE id IN (:ids)")
    suspend fun getItems(ids: List<String>): List<LibraryItemEntity>

    @Query("SELECT * FROM library_items WHERE id IN (:ids)")
    fun observeItems(ids: List<String>): Flow<List<LibraryItemEntity>>

    /**
     * Browse query. Sorting is done in SQL so paging stays stable; `sortKey` is an integer chosen by
     * the repository (0 = recently added, 1 = A–Z, 2 = year, 3 = rating).
     */
    @Query(
        """
        SELECT * FROM library_items
        WHERE (:type IS NULL OR type = :type)
          AND (:genre IS NULL OR genres LIKE '%' || :genre || '%')
        ORDER BY
            CASE WHEN :sortKey = 0 THEN addedAtMs END DESC,
            CASE WHEN :sortKey = 1 THEN sortTitle END COLLATE NOCASE ASC,
            CASE WHEN :sortKey = 2 THEN year END DESC,
            CASE WHEN :sortKey = 3 THEN communityRating END DESC,
            sortTitle COLLATE NOCASE ASC
        """,
    )
    fun observeLibrary(type: String?, genre: String?, sortKey: Int): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items ORDER BY addedAtMs DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<LibraryItemEntity>>

    @Query("SELECT MAX(updatedAtMs) FROM library_items")
    suspend fun maxUpdatedAt(): Long?

    @Query("SELECT COUNT(*) FROM library_items")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM library_items")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM library_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM library_items")
    suspend fun clear()

    @Query(
        """
        SELECT * FROM library_items
        WHERE title LIKE '%' || :query || '%' OR sortTitle LIKE '%' || :query || '%'
        ORDER BY
            CASE WHEN title LIKE :query || '%' THEN 0 ELSE 1 END,
            sortTitle COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun searchOffline(query: String, limit: Int): List<LibraryItemEntity>

    @Query("SELECT DISTINCT genres FROM library_items")
    suspend fun allGenreBlobs(): List<String>
}

@Dao
interface SeasonDao {
    @Upsert
    suspend fun upsertAll(seasons: List<SeasonEntity>)

    @Query("SELECT * FROM seasons WHERE showId = :showId ORDER BY (number = 0), number")
    fun observeForShow(showId: String): Flow<List<SeasonEntity>>

    @Query("SELECT * FROM seasons WHERE showId = :showId ORDER BY (number = 0), number")
    suspend fun getForShow(showId: String): List<SeasonEntity>

    @Query("DELETE FROM seasons WHERE showId = :showId")
    suspend fun deleteForShow(showId: String)
}

@Dao
interface EpisodeDao {
    @Upsert
    suspend fun upsertAll(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM episodes WHERE showId = :showId ORDER BY (seasonNumber = 0), seasonNumber, episodeNumber")
    fun observeForShow(showId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE seasonId = :seasonId ORDER BY episodeNumber")
    suspend fun getForSeason(seasonId: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisode(id: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id IN (:ids)")
    suspend fun getEpisodes(ids: List<String>): List<EpisodeEntity>

    @Query("DELETE FROM episodes WHERE showId = :showId")
    suspend fun deleteForShow(showId: String)
}

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Upsert
    suspend fun upsertAll(progress: List<ProgressEntity>)

    @Query("SELECT * FROM progress WHERE itemId = :itemId")
    suspend fun get(itemId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE itemId = :itemId")
    fun observe(itemId: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE itemId IN (:itemIds)")
    fun observeAll(itemIds: List<String>): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress")
    fun observeEverything(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress")
    suspend fun all(): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE showId = :showId")
    suspend fun getForShow(showId: String): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE syncState != 'SYNCED' ORDER BY updatedAtMs")
    suspend fun pending(): List<ProgressEntity>

    @Query("UPDATE progress SET syncState = :state WHERE itemId = :itemId AND updatedAtMs = :updatedAtMs")
    suspend fun markSyncState(itemId: String, updatedAtMs: Long, state: String)

    @Query("DELETE FROM progress WHERE itemId = :itemId")
    suspend fun delete(itemId: String)

    @Query("SELECT MAX(updatedAtMs) FROM progress WHERE syncState = 'SYNCED'")
    suspend fun lastSyncedUpdatedAt(): Long?

    @Query("DELETE FROM progress")
    suspend fun clear()
}

@Dao
interface MyListDao {
    @Upsert
    suspend fun upsert(entry: MyListEntity)

    @Upsert
    suspend fun upsertAll(entries: List<MyListEntity>)

    @Query("SELECT * FROM my_list WHERE deleted = 0 ORDER BY addedAtMs DESC")
    fun observeAll(): Flow<List<MyListEntity>>

    @Query("SELECT itemId FROM my_list WHERE deleted = 0")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT * FROM my_list WHERE itemId = :itemId")
    suspend fun get(itemId: String): MyListEntity?

    @Query("SELECT * FROM my_list WHERE syncState != 'SYNCED'")
    suspend fun pending(): List<MyListEntity>

    @Query("SELECT itemId FROM my_list")
    suspend fun allIds(): List<String>

    @Query("SELECT itemId FROM my_list WHERE syncState = 'SYNCED' AND deleted = 0")
    suspend fun syncedIds(): List<String>

    @Query("UPDATE my_list SET syncState = :state WHERE itemId = :itemId")
    suspend fun markSyncState(itemId: String, state: String)

    @Query("DELETE FROM my_list WHERE itemId = :itemId")
    suspend fun hardDelete(itemId: String)

    @Query("DELETE FROM my_list")
    suspend fun clear()
}

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY searchedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history")
    suspend fun clear()

    @Transaction
    suspend fun record(query: String, atMs: Long, keep: Int) {
        insert(SearchHistoryEntity(query, atMs))
        trim(keep)
    }

    @Query(
        """
        DELETE FROM search_history WHERE query NOT IN (
            SELECT query FROM search_history ORDER BY searchedAtMs DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(keep: Int)
}

@Dao
interface SyncMarkerDao {
    @Upsert
    suspend fun upsert(marker: SyncMarkerEntity)

    @Query("SELECT * FROM sync_markers WHERE `key` = :key")
    suspend fun get(key: String): SyncMarkerEntity?

    @Query("DELETE FROM sync_markers")
    suspend fun clear()
}
