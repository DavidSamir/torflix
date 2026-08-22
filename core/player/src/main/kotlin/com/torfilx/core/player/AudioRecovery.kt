package com.torfilx.core.player

/** What to do about a title whose picture is playing but whose sound is missing. */
enum class AudioRemedy {
    /** Audio is fine, or there is nothing to judge yet. */
    NOTHING,

    /** The file has no audio stream at all — tell the viewer, but there is nothing to fix. */
    NO_AUDIO_TRACK,

    /** A decodable track exists but was not chosen: clear the filters and select it. */
    RESELECT,

    /** Rebuild the player without tunneled playback and try the same file again. */
    DROP_TUNNELING,

    /** Nothing left to try; explain to the viewer why the film is silent. */
    REPORT,
}

/**
 * Decides how to respond to silent playback.
 *
 * Extracted from [PlaybackController] as a pure function because the situation it handles cannot be
 * reproduced on a desktop JVM — it needs a real device, a real decoder and a file whose audio codec
 * that particular device lacks — and it is precisely the logic that must not regress. The controller
 * keeps the side effects; the decision is testable on its own.
 *
 * The escalation exists because "no audio" has several unrelated causes that look identical from the
 * outside, and the app cannot tell them apart without trying:
 *  - a preferred audio language, or a stale override from the previously played title, filtered out
 *    the only usable track;
 *  - tunneled playback cannot be satisfied by the audio decoder, so the selector ends up with no
 *    audio at all;
 *  - the device genuinely has no decoder for this codec, which no amount of retrying will change.
 *
 * @param stage how far the escalation has already gone for this title.
 */
internal fun audioRemedyFor(
    hasVideo: Boolean,
    tracks: List<AudioTrackState>,
    stage: Int,
    tunnelingEnabled: Boolean,
): AudioRemedy = when {
    // Nothing has been resolved yet: judging now would fire on every intermediate track update.
    !hasVideo -> AudioRemedy.NOTHING

    // A silent film, or a video-only rip. Not a fault, but not something to leave unexplained.
    tracks.isEmpty() -> AudioRemedy.NO_AUDIO_TRACK

    // The normal, healthy case.
    tracks.any { it.isSelected } -> AudioRemedy.NOTHING

    // Something decodable is available and has not been forced yet.
    tracks.any { it.isDecodable } && stage < STAGE_RESELECT -> AudioRemedy.RESELECT

    // Nothing decodable was selectable — tunneling is the next most likely culprit.
    tunnelingEnabled && stage < STAGE_DROP_TUNNELING -> AudioRemedy.DROP_TUNNELING

    else -> AudioRemedy.REPORT
}

/** The minimal view of an audio track the decision needs. */
internal data class AudioTrackState(
    val isSelected: Boolean,
    val isDecodable: Boolean,
)

internal const val STAGE_NONE = 0
internal const val STAGE_RESELECT = 1
internal const val STAGE_DROP_TUNNELING = 2
internal const val STAGE_DONE = 3
