package com.torfilx.core.data.repository

import com.torfilx.core.common.time.TimeProvider
import com.torfilx.core.data.database.MyListDao
import com.torfilx.core.data.database.MyListEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** My List, stored on the device. Ordered most-recently-added first. */
@Singleton
class MyListRepository @Inject constructor(
    private val myListDao: MyListDao,
    private val timeProvider: TimeProvider,
) {

    val itemIds: Flow<Set<String>> = myListDao.observeIds().map { it.toSet() }

    fun observeEntries(): Flow<List<MyListEntity>> = myListDao.observeAll()

    suspend fun isInList(itemId: String): Boolean = myListDao.get(itemId) != null

    suspend fun add(itemId: String) {
        myListDao.upsert(MyListEntity(itemId = itemId, addedAtMs = timeProvider.writeTimestampMs()))
    }

    suspend fun remove(itemId: String) {
        myListDao.hardDelete(itemId)
    }

    suspend fun toggle(itemId: String): Boolean {
        val nowInList = !isInList(itemId)
        if (nowInList) add(itemId) else remove(itemId)
        return nowInList
    }
}
