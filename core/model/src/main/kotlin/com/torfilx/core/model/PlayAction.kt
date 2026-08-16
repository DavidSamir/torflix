package com.torfilx.core.model

/**
 * What the primary button on a details screen (or a card) does.
 *
 * Derived from local progress rather than from a stored flag, so the label is correct the instant
 * the viewer returns from the player.
 */
sealed interface PlayAction {
    /** Nothing playable (e.g. a catalogue entry whose magnets were all invalid). */
    data object Unavailable : PlayAction

    /** Start from the beginning; [restart] is true when the film was already watched. */
    data class Play(val itemId: String, val restart: Boolean) : PlayAction

    /** Continue from [positionMs]. */
    data class Resume(val itemId: String, val positionMs: Long) : PlayAction
}

/** Chooses the primary action for a film. */
object PlayActionResolver {

    fun actionFor(item: MediaItem, progress: PlaybackProgress?, hasPlayableSource: Boolean = true): PlayAction {
        if (!hasPlayableSource) return PlayAction.Unavailable
        return when {
            ResumeRules.isInProgress(progress) ->
                PlayAction.Resume(item.id, ResumeRules.resumePositionMs(progress))

            progress != null && ResumeRules.isWatched(progress) ->
                PlayAction.Play(item.id, restart = true)

            else -> PlayAction.Play(item.id, restart = false)
        }
    }
}
