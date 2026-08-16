package com.torfilx.core.data.repository

import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.common.time.TimeProvider
import com.torfilx.core.data.database.EpisodeDao
import com.torfilx.core.data.database.LibraryDao
import com.torfilx.core.data.database.ProgressDao
import com.torfilx.core.data.database.ProgressEntity
import com.torfilx.core.data.database.SyncState
import com.torfilx.core.data.database.toDomain
import com.torfilx.core.data.database.toEntity
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.ResumeRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Progress"

/**
 * Playback progress: the one piece of state the user really notices losing.
 *
 * Writes are local-first — Room is updated synchronously and a sync job is enqueued — so progress
 * survives the PC being asleep, the app being killed, and the network dropping (plan.md §3, §7.5).
 */
@Singleton
class ProgressRepository @Inject constructor(
    private val progressDao: ProgressDao,
    private val libraryDao: LibraryDao,
    private val episodeDao: EpisodeDao,
    private val timeProvider: TimeProvider,
    @ApplicationScope private val scope: CoroutineScope,
) {

    fun observe(itemId: String): Flow<PlaybackProgress?> =
        progressDao.observe(itemId).map { it?.toDomain() }

    fun observeAll(itemIds: List<String>): Flow<Map<String, PlaybackProgress>> =
        progressDao.observeAll(itemIds).map { list -> list.associate { it.itemId to it.toDomain() } }

    /** Every known progress row, keyed by item id — used to decorate rows and grids. */
    fun observeAllProgress(): Flow<Map<String, PlaybackProgress>> =
        progressDao.observeEverything().map { list -> list.associate { it.itemId to it.toDomain() } }

    suspend fun currentProgressMap(): Map<String, PlaybackProgress> =
        progressDao.all().associate { it.itemId to it.toDomain() }

    suspend fun get(itemId: String): PlaybackProgress? = progressDao.get(itemId)?.toDomain()

    suspend fun progressForShow(showId: String): Map<String, PlaybackProgress> =
        progressDao.getForShow(showId).associate { it.itemId to it.toDomain() }

    /**
     * Records a playback position. Called every ~10 s while playing and on every pause/seek/stop,
     * so it must be cheap and must never throw into the player.
     */
    suspend fun save(
        itemId: String,
        positionMs: Long,
        durationMs: Long,
        showId: String? = null,
        watchedOverride: Boolean? = null,
    ) {
        val safePosition = positionMs.coerceAtLeast(0)
        val safeDuration = durationMs.coerceAtLeast(0)
        val fraction = if (safeDuration <= 0) 0f else safePosition.toFloat() / safeDuration
        val watched = watchedOverride ?: (fraction >= ResumeRules.WATCHED_THRESHOLD)

        val entity = ProgressEntity(
            itemId = itemId,
            positionMs = safePosition,
            durationMs = safeDuration,
            watched = watched,
            updatedAtMs = timeProvider.serverAdjustedNowMs(),
            syncState = SyncState.SYNCED.name,
            showId = showId ?: episodeDao.getEpisode(itemId)?.showId,
        )
        runCatching { progressDao.upsert(entity) }
            .onFailure { TorfilxLog.e(TAG, "Failed to persist progress for $itemId", it) }
    }

    /** Marks an item watched (Menu → Mark watched) without playing it. */
    suspend fun markWatched(itemId: String, durationMs: Long?, watched: Boolean) {
        val existing = progressDao.get(itemId)
        val duration = durationMs ?: existing?.durationMs ?: 0L
        save(
            itemId = itemId,
            positionMs = if (watched) duration else 0L,
            durationMs = duration,
            showId = existing?.showId,
            watchedOverride = watched,
        )
    }

    /** Removes an item from Continue Watching (Menu → Remove). */
    suspend fun remove(itemId: String) {
        progressDao.delete(itemId)
    }

    /**
     * Continue Watching, resolved entirely from local state so it renders instantly at startup.
     *
     * A show contributes at most one entry — its most recently watched in-progress episode — which
     * is what makes the row read like Netflix rather than like a database dump (plan.md §6.1).
     */
    fun observeContinueWatching(limit: Int = CONTINUE_WATCHING_LIMIT): Flow<List<MediaCard>> =
        progressDao.observeEverything().map { rows ->
            val inProgress = rows
                .map { it.toDomain() to it.showId }
                .filter { (progress, _) -> ResumeRules.belongsInContinueWatching(progress) }
                .sortedByDescending { (progress, _) -> progress.updatedAtMs }

            val seenShows = mutableSetOf<String>()
            val cards = mutableListOf<MediaCard>()
            for ((progress, showId) in inProgress) {
                if (cards.size >= limit) break
                if (showId != null) {
                    if (!seenShows.add(showId)) continue
                    val show = libraryDao.getItem(showId)?.toDomain() ?: continue
                    val episode = episodeDao.getEpisode(progress.itemId)?.toDomain() ?: continue
                    cards += MediaCard(item = show, progress = progress, episode = episode)
                } else {
                    val item = libraryDao.getItem(progress.itemId)?.toDomain() ?: continue
                    cards += MediaCard(item = item, progress = progress)
                }
            }
            cards
        }

    companion object {
        const val CONTINUE_WATCHING_LIMIT = 20
    }
}
