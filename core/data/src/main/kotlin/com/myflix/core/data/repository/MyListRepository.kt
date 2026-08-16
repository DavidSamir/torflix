package com.myflix.core.data.repository

import com.myflix.core.common.log.MyflixLog
import com.myflix.core.common.time.TimeProvider
import com.myflix.core.data.database.MyListDao
import com.myflix.core.data.database.MyListEntity
import com.myflix.core.data.database.SyncState
import com.myflix.core.data.remote.MediaRemoteSource
import com.myflix.core.data.sync.SyncScheduler
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
    private val remote: MediaRemoteSource,
    private val timeProvider: TimeProvider,
    private val syncScheduler: SyncScheduler,
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
                syncState = SyncState.PENDING.name,
                deleted = false,
            ),
        )
        syncScheduler.enqueueMyListSync()
    }

    suspend fun remove(itemId: String) {
        val existing = myListDao.get(itemId)
        myListDao.upsert(
            MyListEntity(
                itemId = itemId,
                addedAtMs = existing?.addedAtMs ?: timeProvider.serverAdjustedNowMs(),
                syncState = SyncState.PENDING.name,
                deleted = true,
            ),
        )
        syncScheduler.enqueueMyListSync()
    }

    suspend fun toggle(itemId: String): Boolean {
        val nowInList = !isInList(itemId)
        if (nowInList) add(itemId) else remove(itemId)
        return nowInList
    }

    /** Pushes pending additions/removals. Returns false when a retry is needed. */
    suspend fun pushPending(): Boolean {
        var allOk = true
        for (entry in myListDao.pending()) {
            val result = runCatching {
                if (entry.deleted) remote.removeFromMyList(entry.itemId) else remote.addToMyList(entry.itemId)
            }
            if (result.isSuccess) {
                if (entry.deleted) {
                    myListDao.hardDelete(entry.itemId) // tombstone has served its purpose
                } else {
                    myListDao.markSyncState(entry.itemId, SyncState.SYNCED.name)
                }
            } else {
                allOk = false
                MyflixLog.w(TAG, "My List sync failed for ${entry.itemId}", result.exceptionOrNull())
                myListDao.markSyncState(entry.itemId, SyncState.FAILED.name)
            }
        }
        return allOk
    }

    /**
     * Replaces the synced portion of the list with the server's copy, keeping pending changes.
     *
     * Entries already stored locally keep their original `addedAtMs` so the row does not reshuffle
     * on every sync (the list is ordered most-recently-added first).
     */
    suspend fun pullFromServer() {
        val serverIds = remote.myList().toSet()
        val pendingIds = myListDao.pending().map { it.itemId }.toSet()
        val knownIds = myListDao.allIds().toSet()
        val now = timeProvider.serverAdjustedNowMs()

        val toInsert = (serverIds - knownIds).map { itemId ->
            MyListEntity(itemId = itemId, addedAtMs = now, syncState = SyncState.SYNCED.name)
        }
        if (toInsert.isNotEmpty()) myListDao.upsertAll(toInsert)

        // Anything locally marked SYNCED that the server no longer has was removed on another client.
        val stale = myListDao.syncedIds().toSet() - serverIds - pendingIds
        stale.forEach { myListDao.hardDelete(it) }
    }
}
