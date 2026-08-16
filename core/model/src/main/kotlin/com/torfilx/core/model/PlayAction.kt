package com.torfilx.core.model

/**
 * What the primary button on a details screen does.
 *
 * Deciding this in the app (rather than trusting a server field) keeps the label correct the instant
 * the user returns from the player, because it is derived from local progress (plan.md §6.3–6.4).
 */
sealed interface PlayAction {
    /** No playable content (e.g. a show whose episodes failed to load). */
    data object Unavailable : PlayAction

    /** Start a movie from the beginning. */
    data class PlayMovie(val itemId: String, val restart: Boolean) : PlayAction

    /** Resume a movie at [positionMs]. */
    data class ResumeMovie(val itemId: String, val positionMs: Long) : PlayAction

    /** Play an episode; [positionMs] is 0 for a fresh start. */
    data class PlayEpisode(
        val episode: Episode,
        val positionMs: Long,
        val kind: EpisodeActionKind,
    ) : PlayAction

    enum class EpisodeActionKind { RESUME, NEXT_UP, START_OVER }
}

/** Chooses the next episode to play and the resulting primary action for a show. */
object NextEpisodeSelector {

    /**
     * Priority (plan.md §6.4):
     * 1. an episode currently in progress (earliest in aired order),
     * 2. otherwise the first unwatched episode in aired order,
     * 3. otherwise the very first episode again.
     */
    fun primaryAction(
        details: ShowDetails,
        progressByItemId: Map<String, PlaybackProgress>,
    ): PlayAction {
        val episodes = details.episodesInAiredOrder
        if (episodes.isEmpty()) return PlayAction.Unavailable

        val inProgress = episodes.firstOrNull { ResumeRules.isInProgress(progressByItemId[it.id]) }
        if (inProgress != null) {
            val progress = progressByItemId.getValue(inProgress.id)
            return PlayAction.PlayEpisode(
                episode = inProgress,
                positionMs = ResumeRules.resumePositionMs(progress),
                kind = PlayAction.EpisodeActionKind.RESUME,
            )
        }

        val firstUnwatched = episodes.firstOrNull { episode ->
            val progress = progressByItemId[episode.id]
            progress == null || !ResumeRules.isWatched(progress)
        }
        if (firstUnwatched != null) {
            return PlayAction.PlayEpisode(
                episode = firstUnwatched,
                positionMs = 0L,
                kind = PlayAction.EpisodeActionKind.NEXT_UP,
            )
        }

        return PlayAction.PlayEpisode(
            episode = episodes.first(),
            positionMs = 0L,
            kind = PlayAction.EpisodeActionKind.START_OVER,
        )
    }

    /** The episode that follows [currentEpisodeId] in aired order, or null if it is the last one. */
    fun nextEpisodeAfter(details: ShowDetails, currentEpisodeId: String): Episode? {
        val episodes = details.episodesInAiredOrder
        val index = episodes.indexOfFirst { it.id == currentEpisodeId }
        return if (index >= 0 && index < episodes.lastIndex) episodes[index + 1] else null
    }

    /** The episode that precedes [currentEpisodeId] in aired order, or null if it is the first one. */
    fun previousEpisodeBefore(details: ShowDetails, currentEpisodeId: String): Episode? {
        val episodes = details.episodesInAiredOrder
        val index = episodes.indexOfFirst { it.id == currentEpisodeId }
        return if (index > 0) episodes[index - 1] else null
    }

    /** Primary action for a movie. */
    fun movieAction(item: MediaItem, progress: PlaybackProgress?): PlayAction = when {
        ResumeRules.isInProgress(progress) ->
            PlayAction.ResumeMovie(item.id, ResumeRules.resumePositionMs(progress))

        progress != null && ResumeRules.isWatched(progress) ->
            PlayAction.PlayMovie(item.id, restart = true)

        else -> PlayAction.PlayMovie(item.id, restart = false)
    }
}
