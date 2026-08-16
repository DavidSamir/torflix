package com.torfilx.core.model

/**
 * Playback position for one playable item (a movie or a single episode).
 *
 * `updatedAtMs` is the conflict-resolution key: the newest write wins when the local database and
 * the server disagree (plan.md §7.5).
 */
data class PlaybackProgress(
    val itemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean = false,
    val updatedAtMs: Long = 0L,
) {
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
}

/**
 * The rules that decide whether playback resumes, whether an item counts as watched, and whether it
 * still belongs in Continue Watching. Pure functions so they are unit-testable without Android
 * (plan.md §7.5).
 */
object ResumeRules {
    /** Below this fraction the user has effectively not started the item. */
    const val START_THRESHOLD: Float = 0.02f

    /** At or above this fraction the item counts as watched and leaves Continue Watching. */
    const val WATCHED_THRESHOLD: Float = 0.90f

    /** Positions shorter than this are treated as "just started" — restart instead of resuming. */
    const val MIN_RESUME_POSITION_MS: Long = 30_000L

    /** With less than this left, resuming is pointless — the item is finished for practical purposes. */
    const val MIN_REMAINING_MS: Long = 30_000L

    fun isWatched(progress: PlaybackProgress): Boolean =
        progress.watched || progress.fraction >= WATCHED_THRESHOLD

    fun isInProgress(progress: PlaybackProgress?): Boolean {
        if (progress == null || progress.durationMs <= 0L) return false
        if (progress.watched) return false
        return progress.fraction > START_THRESHOLD &&
            progress.fraction < WATCHED_THRESHOLD &&
            progress.positionMs > MIN_RESUME_POSITION_MS &&
            progress.remainingMs > MIN_REMAINING_MS
    }

    /** Position playback should actually start from. */
    fun resumePositionMs(progress: PlaybackProgress?): Long =
        if (isInProgress(progress)) progress!!.positionMs else 0L

    /** Whether the item should be listed in Continue Watching. */
    fun belongsInContinueWatching(progress: PlaybackProgress?): Boolean = isInProgress(progress)
}
