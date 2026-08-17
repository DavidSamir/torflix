package com.torfilx.core.data.backup

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.data.database.MyListDao
import com.torfilx.core.data.database.MyListEntity
import com.torfilx.core.data.database.ProgressDao
import com.torfilx.core.data.database.ProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

class UserDataBackupTest {

    private fun backupFor(p: ProgressDao, m: MyListDao) =
        UserDataBackup(p, m, Json, UnconfinedTestDispatcher())

    @Test
    fun `export then import into an empty store restores everything`() = runTest {
        val source = FakeProgressDao().apply {
            rows["a"] = ProgressEntity("a", 1000, 5000, false, 10)
            rows["b"] = ProgressEntity("b", 5000, 5000, true, 20)
        }
        val sourceList = FakeMyListDao().apply { rows["x"] = MyListEntity("x", 100) }
        val json = backupFor(source, sourceList).exportToJson()

        val targetProgress = FakeProgressDao()
        val targetList = FakeMyListDao()
        val result = backupFor(targetProgress, targetList).importFromJson(json)

        assertThat(result.progressRestored).isEqualTo(2)
        assertThat(result.myListRestored).isEqualTo(1)
        assertThat(targetProgress.rows["a"]?.positionMs).isEqualTo(1000)
        assertThat(targetProgress.rows["b"]?.watched).isTrue()
        assertThat(targetList.rows).containsKey("x")
    }

    @Test
    fun `import never overwrites a newer local position`() = runTest {
        // Backup captured an older position (updatedAtMs = 100).
        val old = FakeProgressDao().apply { rows["a"] = ProgressEntity("a", 1000, 10_000, false, 100) }
        val backupJson = backupFor(old, FakeMyListDao()).exportToJson()

        // Local has since advanced (updatedAtMs = 200).
        val local = FakeProgressDao().apply { rows["a"] = ProgressEntity("a", 9000, 10_000, false, 200) }
        backupFor(local, FakeMyListDao()).importFromJson(backupJson)

        assertThat(local.rows["a"]?.positionMs).isEqualTo(9000) // newer local wins
    }

    @Test
    fun `import applies a backup position that is newer than local`() = runTest {
        val newer = FakeProgressDao().apply { rows["a"] = ProgressEntity("a", 8000, 10_000, false, 300) }
        val backupJson = backupFor(newer, FakeMyListDao()).exportToJson()

        val local = FakeProgressDao().apply { rows["a"] = ProgressEntity("a", 1000, 10_000, false, 100) }
        backupFor(local, FakeMyListDao()).importFromJson(backupJson)

        assertThat(local.rows["a"]?.positionMs).isEqualTo(8000) // newer backup applied
    }
}

private class FakeProgressDao : ProgressDao {
    val rows = mutableMapOf<String, ProgressEntity>()
    override suspend fun upsert(progress: ProgressEntity) { rows[progress.itemId] = progress }
    override suspend fun get(itemId: String): ProgressEntity? = rows[itemId]
    override fun observe(itemId: String): Flow<ProgressEntity?> = flowOf(rows[itemId])
    override fun observeEverything(): Flow<List<ProgressEntity>> = emptyFlow()
    override suspend fun all(): List<ProgressEntity> = rows.values.toList()
    override suspend fun delete(itemId: String) { rows.remove(itemId) }
    override suspend fun clear() { rows.clear() }
}

private class FakeMyListDao : MyListDao {
    val rows = mutableMapOf<String, MyListEntity>()
    override suspend fun upsert(entry: MyListEntity) { rows[entry.itemId] = entry }
    override fun observeAll(): Flow<List<MyListEntity>> = emptyFlow()
    override fun observeIds(): Flow<List<String>> = emptyFlow()
    override suspend fun all(): List<MyListEntity> = rows.values.toList()
    override suspend fun get(itemId: String): MyListEntity? = rows[itemId]
    override suspend fun hardDelete(itemId: String) { rows.remove(itemId) }
    override suspend fun clear() { rows.clear() }
}
