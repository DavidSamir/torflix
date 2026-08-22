package com.torfilx.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The arithmetic that turns libtorrent's session counters into a lifetime total.
 *
 * This is the whole feature's credibility in one function. A contribution figure that quietly loses
 * bytes is worse than no figure at all — it tells someone who has seeded 40 GB that they seeded 200
 * MB, and there is no way for them to know it is wrong.
 */
class ContributionDeltaTest {

    @Test
    fun `the first reading of a session counts in full`() {
        // Nothing has been seen yet, so everything libtorrent reports is new to us.
        assertThat(contributionDelta(lastSeen = null, current = 5_000)).isEqualTo(5_000)
    }

    @Test
    fun `a rising counter contributes only the difference`() {
        assertThat(contributionDelta(lastSeen = 5_000, current = 8_000)).isEqualTo(3_000)
    }

    @Test
    fun `an unchanged counter contributes nothing`() {
        assertThat(contributionDelta(lastSeen = 5_000, current = 5_000)).isEqualTo(0)
    }

    @Test
    fun `a counter that went backwards is a restarted session, not a decrease`() {
        // libtorrent counts per session. After a restart it begins again at zero, so a smaller value
        // than last time means "new session", and the whole of it is newly shared. Treating this as
        // a decrease — and clamping to zero — is the bug that loses everything shared after a
        // restart, which on a TV that is left on for weeks is nearly all of it.
        assertThat(contributionDelta(lastSeen = 9_000, current = 400)).isEqualTo(400)
    }

    @Test
    fun `a negative reading is ignored rather than trusted`() {
        // Defensive: a native counter should never be negative, but if it is, adding it would
        // silently reduce a lifetime total that is supposed to only ever grow.
        assertThat(contributionDelta(lastSeen = 100, current = -1)).isEqualTo(0)
    }

    @Test
    fun `folding a whole session never loses or invents bytes`() {
        // The property that matters: replaying a plausible sequence of readings, including a
        // restart, must total exactly what was actually transferred.
        val readings = listOf(0L, 100L, 100L, 250L, 900L, /* restart */ 50L, 300L, 300L, 512L)
        var lastSeen: Long? = null
        var lifetime = 0L
        for (reading in readings) {
            lifetime += contributionDelta(lastSeen, reading)
            lastSeen = reading
        }
        // 900 in the first session, 512 in the second.
        assertThat(lifetime).isEqualTo(900 + 512)
    }

    @Test
    fun `a lifetime total is monotonic across any sequence of readings`() {
        var lastSeen: Long? = null
        var lifetime = 0L
        for (reading in listOf(10L, 5L, 7L, 0L, 3L, 9_999L, 1L)) {
            val before = lifetime
            lifetime += contributionDelta(lastSeen, reading)
            lastSeen = reading
            assertThat(lifetime).isAtLeast(before)
        }
    }
}
