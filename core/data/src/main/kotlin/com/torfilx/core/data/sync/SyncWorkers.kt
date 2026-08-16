package com.torfilx.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.torfilx.core.common.error.DataError
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.MyListRepository
import com.torfilx.core.data.repository.ProgressRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

private const val TAG = "SyncWorker"

/**
 * Uploads pending playback positions, then pulls the server's view back down.
 *
 * Returning `retry()` (rather than `failure()`) for transient errors is what makes progress survive
 * a sleeping server: WorkManager retries with exponential backoff until it succeeds.
 */
@HiltWorker
class ProgressSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val progressRepository: ProgressRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runSync(TAG, "progress") {
        val pushed = progressRepository.pushPending()
        progressRepository.reconcileFromServer()
        pushed
    }

    companion object {
        const val NAME = "sync-progress"
    }
}

@HiltWorker
class MyListSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val myListRepository: MyListRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runSync(TAG, "my-list") {
        val pushed = myListRepository.pushPending()
        myListRepository.pullFromServer()
        pushed
    }

    companion object {
        const val NAME = "sync-my-list"
    }
}

@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaRepository: MediaRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runSync(TAG, "library") {
        mediaRepository.refreshLibrary()
        mediaRepository.refreshHome()
        true
    }

    companion object {
        const val NAME = "sync-library"
    }
}

/**
 * Shared error policy: retry what can succeed later, give up on what cannot.
 *
 * An unconfigured server or a rejected token will never fix itself by retrying, and retrying them
 * would keep the radio awake for nothing.
 */
private suspend fun runSync(
    tag: String,
    label: String,
    block: suspend () -> Boolean,
): androidx.work.ListenableWorker.Result = try {
    if (block()) {
        androidx.work.ListenableWorker.Result.success()
    } else {
        androidx.work.ListenableWorker.Result.retry()
    }
} catch (error: DataError) {
    TorfilxLog.w(tag, "$label sync failed: ${error::class.simpleName}", error)
    when (error) {
        is DataError.NotConfigured, is DataError.Unauthorized ->
            androidx.work.ListenableWorker.Result.failure()

        else -> androidx.work.ListenableWorker.Result.retry()
    }
} catch (error: Exception) {
    TorfilxLog.e(tag, "$label sync crashed", error)
    androidx.work.ListenableWorker.Result.retry()
}
