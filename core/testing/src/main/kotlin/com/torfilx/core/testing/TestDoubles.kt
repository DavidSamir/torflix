package com.torfilx.core.testing

import com.torfilx.core.common.time.TimeProvider
import com.torfilx.core.model.Images
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.PlaybackProgress

/** A clock the test controls, so `updatedAt` ordering is deterministic. */
class FakeTimeProvider(
    var now: Long = FIXED_NOW_MS,
    var elapsed: Long = 0L,
) : TimeProvider {
    override fun nowMs(): Long = now

    override fun elapsedRealtimeMs(): Long = elapsed

    fun advance(byMs: Long) {
        now += byMs
        elapsed += byMs
    }

    companion object {
        /** 2026-01-01T00:00:00Z. */
        const val FIXED_NOW_MS = 1_767_225_600_000L
    }
}

/** Deterministic fixtures shared by unit and instrumented tests. */
object Fixtures {

    const val MINUTE_MS = 60_000L

    fun film(
        id: String = "catalog-the-kid-1921",
        title: String = "The Kid",
        runtimeMs: Long = 68 * MINUTE_MS,
        withArtwork: Boolean = true,
    ) = MediaItem(
        id = id,
        title = title,
        sortTitle = title,
        year = 1921,
        runtimeMs = runtimeMs,
        communityRating = 8.2,
        overview = "A tramp adopts an abandoned child.",
        genres = listOf("Comedy", "Drama"),
        images = if (withArtwork) Images(poster = "http://poster/$id") else Images(),
    )

    fun progress(
        itemId: String = "catalog-the-kid-1921",
        positionMs: Long = 20 * MINUTE_MS,
        durationMs: Long = 68 * MINUTE_MS,
        watched: Boolean = false,
        updatedAtMs: Long = FakeTimeProvider.FIXED_NOW_MS,
    ) = PlaybackProgress(itemId, positionMs, durationMs, watched, updatedAtMs)

    fun card(
        item: MediaItem = film(),
        progress: PlaybackProgress? = null,
        inMyList: Boolean = false,
    ) = MediaCard(item = item, progress = progress, inMyList = inMyList)
}
