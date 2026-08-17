package com.torfilx.core.data.backup

import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.data.database.MyListDao
import com.torfilx.core.data.database.MyListEntity
import com.torfilx.core.data.database.ProgressDao
import com.torfilx.core.data.database.ProgressEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Export and restore the viewer's own state — Continue Watching positions and My List.
 *
 * The catalogue ships with the app and search history is transient, so those are deliberately left
 * out; what a viewer would actually miss after a reinstall or a stick swap is where they were up to
 * and what they had saved. Fire OS has no Google backup transport, so this is a manual, file-based
 * backup the user can copy off and back on (e.g. with adb), which is the only data-survival path a
 * sideloaded app without a backend can honestly offer.
 */
@Singleton
class UserDataBackup @Inject constructor(
    private val progressDao: ProgressDao,
    private val myListDao: MyListDao,
    private val json: Json,
    @Dispatcher(TorfilxDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    @Serializable
    private data class Backup(
        val version: Int = FORMAT_VERSION,
        val progress: List<Progress> = emptyList(),
        val myList: List<MyListItem> = emptyList(),
    )

    @Serializable
    private data class Progress(
        val itemId: String,
        val positionMs: Long,
        val durationMs: Long,
        val watched: Boolean,
        val updatedAtMs: Long,
    )

    @Serializable
    private data class MyListItem(val itemId: String, val addedAtMs: Long)

    /** Serialises the current watch data to a JSON string. */
    suspend fun exportToJson(): String = withContext(ioDispatcher) {
        val backup = Backup(
            progress = progressDao.all().map {
                Progress(it.itemId, it.positionMs, it.durationMs, it.watched, it.updatedAtMs)
            },
            myList = myListDao.all().map { MyListItem(it.itemId, it.addedAtMs) },
        )
        json.encodeToString(Backup.serializer(), backup)
    }

    /**
     * Merges a previously exported JSON back in. Existing rows are upserted, so a restore never
     * loses newer local data than the backup — the more-recent position wins.
     */
    suspend fun importFromJson(text: String): Result = withContext(ioDispatcher) {
        val backup = json.decodeFromString(Backup.serializer(), text)
        backup.progress.forEach { p ->
            val existing = progressDao.get(p.itemId)
            if (existing == null || existing.updatedAtMs < p.updatedAtMs) {
                progressDao.upsert(
                    ProgressEntity(p.itemId, p.positionMs, p.durationMs, p.watched, p.updatedAtMs),
                )
            }
        }
        backup.myList.forEach { m ->
            if (myListDao.get(m.itemId) == null) myListDao.upsert(MyListEntity(m.itemId, m.addedAtMs))
        }
        Result(progressRestored = backup.progress.size, myListRestored = backup.myList.size)
    }

    data class Result(val progressRestored: Int, val myListRestored: Int)

    private companion object {
        const val FORMAT_VERSION = 1
    }
}
