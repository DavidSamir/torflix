package com.torfilx.core.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.torfilx.core.common.log.TorfilxLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Sync"

/**
 * Enqueues outbox uploads.
 *
 * Work is *unique and replaceable* per queue: a burst of ten progress writes while scrubbing
 * collapses into one upload rather than ten, which matters on a device that reports every 10 s
 * (plan.md §8.2).
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueProgressSync() = enqueue(ProgressSyncWorker.NAME) {
        OneTimeWorkRequestBuilder<ProgressSyncWorker>()
    }

    fun enqueueMyListSync() = enqueue(MyListSyncWorker.NAME) {
        OneTimeWorkRequestBuilder<MyListSyncWorker>()
    }

    fun enqueueLibraryRefresh() = enqueue(LibrarySyncWorker.NAME) {
        OneTimeWorkRequestBuilder<LibrarySyncWorker>()
    }

    private inline fun <reified B : androidx.work.WorkRequest.Builder<*, *>> enqueue(
        name: String,
        builder: () -> B,
    ) {
        runCatching {
            val request = builder()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(
                name,
                ExistingWorkPolicy.REPLACE,
                request as androidx.work.OneTimeWorkRequest,
            )
        }.onFailure { TorfilxLog.w(TAG, "Could not enqueue $name", it) }
    }

    fun cancelAll() {
        runCatching {
            workManager.cancelUniqueWork(ProgressSyncWorker.NAME)
            workManager.cancelUniqueWork(MyListSyncWorker.NAME)
            workManager.cancelUniqueWork(LibrarySyncWorker.NAME)
        }
    }

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
