package com.myflix.core.ui.util

import com.myflix.core.model.Episode
import com.myflix.core.model.MediaItem
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Display formatting.
 *
 * Kept free of Android resources so it is unit-testable; the few user-visible words ("left", "min")
 * are supplied by the caller from string resources where localisation matters.
 */
object Format {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS

    /** `2h 14m`, `48m`, `0m` — never an empty string. */
    fun runtime(ms: Long?): String {
        if (ms == null || ms <= 0) return ""
        val hours = ms / HOUR_MS
        val minutes = ((ms % HOUR_MS) + MINUTE_MS / 2) / MINUTE_MS // round to nearest minute
        val (h, m) = if (minutes == 60L) hours + 1 to 0L else hours to minutes
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    /** `1:12:03` / `12:03` — used on the player timeline where every second matters. */
    fun timecode(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val totalSeconds = safe / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** `S2:E4` — the compact form used on cards and in the player title. */
    fun episodeCode(episode: Episode): String = "S${episode.seasonNumber}:E${episode.episodeNumber}"

    /** Meta line for details/hero: `2024 · 2h 14m · 16+ · Action, Sci-Fi`. */
    fun metaLine(item: MediaItem, maxGenres: Int = 2): String = buildList {
        item.year?.let { add(it.toString()) }
        runtime(item.runtimeMs).takeIf { it.isNotEmpty() }?.let { add(it) }
        item.seasonCount?.let { count ->
            add(if (count == 1) "1 season" else "$count seasons")
        }
        item.ageRating?.takeIf { it.isNotBlank() }?.let { add(it) }
        item.genres.take(maxGenres).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
    }.joinToString(" · ")

    /** `8.4` rating badge text, or null when the server gave no rating. */
    fun rating(value: Double?): String? =
        value?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }

    /** Percentage complete, 0..100, for accessibility descriptions. */
    fun percentComplete(positionMs: Long, durationMs: Long): Int =
        if (durationMs <= 0) 0 else ((positionMs.toDouble() / durationMs) * 100).roundToInt().coerceIn(0, 100)
}
