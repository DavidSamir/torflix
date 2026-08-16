package com.torfilx.core.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE itemId = :itemId")
    suspend fun get(itemId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE itemId = :itemId")
    fun observe(itemId: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress")
    fun observeEverything(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress")
    suspend fun all(): List<ProgressEntity>

    @Query("DELETE FROM progress WHERE itemId = :itemId")
    suspend fun delete(itemId: String)

    @Query("DELETE FROM progress")
    suspend fun clear()
}

@Dao
interface MyListDao {
    @Upsert
    suspend fun upsert(entry: MyListEntity)

    @Query("SELECT * FROM my_list ORDER BY addedAtMs DESC")
    fun observeAll(): Flow<List<MyListEntity>>

    @Query("SELECT itemId FROM my_list")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT * FROM my_list WHERE itemId = :itemId")
    suspend fun get(itemId: String): MyListEntity?

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

    /** Records a search and trims the history to the most recent [keep] entries. */
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
