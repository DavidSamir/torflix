package com.torfilx.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResumeRulesTest {

    private fun progress(
        positionMs: Long,
        durationMs: Long = TWO_HOURS,
        watched: Boolean = false,
    ) = PlaybackProgress("m1", positionMs, durationMs, watched)

    @Test
    fun `item just started is not resumable`() {
        // 20 s into a 2 h movie: below both the 2% and the 30 s floors.
        assertThat(ResumeRules.isInProgress(progress(20_000))).isFalse()
        assertThat(ResumeRules.resumePositionMs(progress(20_000))).isEqualTo(0L)
    }

    @Test
    fun `position past the 30 second floor but below 2 percent is not resumable`() {
        // 40 s into a 10 h recording is 0.1% — the user effectively never started it.
        assertThat(ResumeRules.isInProgress(progress(40_000, durationMs = TEN_HOURS))).isFalse()
    }

    @Test
    fun `midway item is resumable at its exact position`() {
        val p = progress(45 * MINUTE)
        assertThat(ResumeRules.isInProgress(p)).isTrue()
        assertThat(ResumeRules.resumePositionMs(p)).isEqualTo(45 * MINUTE)
        assertThat(ResumeRules.belongsInContinueWatching(p)).isTrue()
    }

    @Test
    fun `item past 90 percent counts as watched and leaves continue watching`() {
        val p = progress((TWO_HOURS * 0.95).toLong())
        assertThat(ResumeRules.isWatched(p)).isTrue()
        assertThat(ResumeRules.isInProgress(p)).isFalse()
        assertThat(ResumeRules.belongsInContinueWatching(p)).isFalse()
        assertThat(ResumeRules.resumePositionMs(p)).isEqualTo(0L)
    }

    @Test
    fun `explicit watched flag wins over position`() {
        val p = progress(30 * MINUTE, watched = true)
        assertThat(ResumeRules.isWatched(p)).isTrue()
        assertThat(ResumeRules.isInProgress(p)).isFalse()
    }

    @Test
    fun `item with under 30 seconds remaining is not resumable`() {
        // 89% of a 4-minute clip is inside the thresholds by fraction but has 26 s left.
        val p = progress(214_000, durationMs = 240_000)
        assertThat(p.fraction).isLessThan(ResumeRules.WATCHED_THRESHOLD)
        assertThat(ResumeRules.isInProgress(p)).isFalse()
    }

    @Test
    fun `zero duration never crashes or resumes`() {
        val p = progress(1_000, durationMs = 0)
        assertThat(p.fraction).isEqualTo(0f)
        assertThat(ResumeRules.isInProgress(p)).isFalse()
        assertThat(p.remainingMs).isEqualTo(0L)
    }

    @Test
    fun `null progress is never resumable`() {
        assertThat(ResumeRules.isInProgress(null)).isFalse()
        assertThat(ResumeRules.resumePositionMs(null)).isEqualTo(0L)
        assertThat(ResumeRules.belongsInContinueWatching(null)).isFalse()
    }

    @Test
    fun `fraction is clamped when the server reports a position past the end`() {
        assertThat(progress(TWO_HOURS * 2).fraction).isEqualTo(1f)
        assertThat(progress(-5_000).fraction).isEqualTo(0f)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val TWO_HOURS = 2 * 60 * MINUTE
        const val TEN_HOURS = 10 * 60 * MINUTE
    }
}
