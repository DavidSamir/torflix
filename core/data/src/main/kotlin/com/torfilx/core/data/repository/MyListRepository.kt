package com.torfilx.core.data.repository

import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.common.time.TimeProvider
import com.torfilx.core.data.database.MyListDao
import com.torfilx.core.data.database.MyListEntity
import com.torfilx.core.data.database.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MyList"

/**
 * My List, local-first with tombstones.
 *
 * A removal is stored as a tombstone rather than a delete so the removal can still be pushed to the
 * server after the app restarts; without it, a delete made while offline would silently come back on
 * the next sync (plan.md §8.2).
 */
@Singleton
class MyListRepository @Inject constructor(
    private val myListDao: MyListDao,
    private val timeProvider: TimeProvider,
) {

    val itemIds: Flow<Set<String>> = myListDao.observeIds().map { it.toSet() }

    fun observeEntries(): Flow<List<MyListEntity>> = myListDao.observeAll()

    suspend fun isInList(itemId: String): Boolean =
        myListDao.get(itemId)?.let { !it.deleted } ?: false

    suspend fun add(itemId: String) {
        myListDao.upsert(
            MyListEntity(
                itemId = itemId,
                addedAtMs = timeProvider.serverAdjustedNowMs(),
                syncState = SyncState.SYNCED.name,
                deleted = false,
            ),
        )
    }

    suspend fun remove(itemId: String) {
        val existing = myListDao.get(itemId)
        myListDao.upsert(
            MyListEntity(
                itemId = itemId,
                addedAtMs = existing?.addedAtMs ?: timeProvider.serverAdjustedNowMs(),
                syncState = SyncState.SYNCED.name,
                deleted = true,
            ),
        )
    }

    suspend fun toggle(itemId: String): Boolean {
        val nowInList = !isInList(itemId)
        if (nowInList) add(itemId) else remove(itemId)
        return nowInList
    }

}
