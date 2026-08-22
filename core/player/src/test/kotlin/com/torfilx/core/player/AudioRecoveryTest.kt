package com.torfilx.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The escalation that rescues a film playing without sound.
 *
 * This was reported from the field as "a lot of movies do not have audio — I see the video playing
 * but the audio is nothing, not for all of them but quite often". It is invisible to every automatic
 * check because ExoPlayer does not treat it as an error: when no audio track can be decoded, the
 * audio renderer selects nothing and the video plays on, silently and successfully.
 *
 * These tests pin the order of remedies and, just as importantly, that each is tried **once** — an
 * escalation that can loop would re-open the same title forever on a device that simply lacks the
 * codec.
 */
class AudioRecoveryTest {

    private fun track(selected: Boolean = false, decodable: Boolean = true) =
        AudioTrackState(isSelected = selected, isDecodable = decodable)

    @Test
    fun `nothing is done before the video track is known`() {
        // Track updates arrive in stages; acting on the first one would fire on every title.
        assertThat(
            audioRemedyFor(
                hasVideo = false,
                tracks = emptyList(),
                stage = STAGE_NONE,
                tunnelingEnabled = true,
            ),
        ).isEqualTo(AudioRemedy.NOTHING)
    }

    @Test
    fun `a selected audio track means all is well`() {
        assertThat(
            audioRemedyFor(
                hasVideo = true,
                tracks = listOf(track(selected = true)),
                stage = STAGE_NONE,
                tunnelingEnabled = true,
            ),
        ).isEqualTo(AudioRemedy.NOTHING)
    }

    @Test
    fun `a file with no audio stream is reported, not retried`() {
        // A silent film or a video-only rip. Retrying would achieve nothing.
        assertThat(
            audioRemedyFor(
                hasVideo = true,
                tracks = emptyList(),
                stage = STAGE_NONE,
                tunnelingEnabled = true,
            ),
        ).isEqualTo(AudioRemedy.NO_AUDIO_TRACK)
    }

    @Test
    fun `a decodable but unselected track is re-selected first`() {
        // The cheapest and most common fix: a language preference or a stale override from the
        // previous title filtered out a track this device can play perfectly well.
        assertThat(
            audioRemedyFor(
                hasVideo = true,
                tracks = listOf(track(selected = false, decodable = true)),
                stage = STAGE_NONE,
                tunnelingEnabled = true,
            ),
        ).isEqualTo(AudioRemedy.RESELECT)
    }

    @Test
    fun `re-selection is not attempted twice`() {
        // Having already forced the decodable track and still having silence, the next remedy is due.
        assertThat(
            audioRemedyFor(
                hasVideo = true,
                tracks = listOf(track(selected = false, decodable = true)),
                stage = STAGE_RESELECT,
                tunnelingEnabled = true,
            ),
        ).isEqualTo(AudioRemedy.DROP_TUNNELING)
    }

    @Test
    fun `with no decodable track at all, tunneling is dropped before giving up`() {
        // The capability probe only inspects video decoders, so tunneling cannot be ruled out up
        // front as the reason the selector found nothing.
        assertThat(
            audioRemedyFor(
                hasVideo = true,
                tracks = listOf(track(selected = false, decodable = false)),
                stage = STAGE_NONE,
                tunnelingEnabled = true,
            ),
        ).isEqualTo(AudioRemedy.DROP_TUNNELING)
    }

    @Test
    fun `tunneling already off means there is nothing left but to explain`() {
        assertThat(
            audioRemedyFor(
                hasVideo = true,
                tracks = listOf(track(selected = false, decodable = false)),
                stage = STAGE_NONE,
                tunnelingEnabled = false,
            ),
        ).isEqualTo(AudioRemedy.REPORT)
    }

    @Test
    fun `after both remedies the viewer is told why`() {
        assertThat(
            audioRemedyFor(
                hasVideo = true,
                tracks = listOf(track(selected = false, decodable = false)),
                stage = STAGE_DROP_TUNNELING,
                tunnelingEnabled = true,
            ),
        ).isEqualTo(AudioRemedy.REPORT)
    }

    @Test
    fun `the escalation always terminates`() {
        // The property that matters most: whatever the track situation, repeatedly applying the
        // remedy and advancing the stage reaches a state that asks for no further action. A cycle
        // here would re-open the same title indefinitely on an unlucky device.
        val trackSets = listOf(
            emptyList(),
            listOf(track(selected = false, decodable = false)),
            listOf(track(selected = false, decodable = true)),
            listOf(track(selected = false, decodable = false), track(selected = false, decodable = true)),
        )
        for (tracks in trackSets) {
            for (tunneling in listOf(true, false)) {
                var stage = STAGE_NONE
                var steps = 0
                while (steps < 10) {
                    val remedy = audioRemedyFor(true, tracks, stage, tunneling)
                    if (remedy == AudioRemedy.NOTHING ||
                        remedy == AudioRemedy.REPORT ||
                        remedy == AudioRemedy.NO_AUDIO_TRACK
                    ) {
                        break
                    }
                    stage = when (remedy) {
                        AudioRemedy.RESELECT -> STAGE_RESELECT
                        AudioRemedy.DROP_TUNNELING -> STAGE_DROP_TUNNELING
                        else -> STAGE_DONE
                    }
                    steps++
                }
                assertThat(steps).isLessThan(4)
            }
        }
    }
}
