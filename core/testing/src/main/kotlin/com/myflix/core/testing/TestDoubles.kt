package com.myflix.core.testing

import com.myflix.core.common.time.TimeProvider

/** A clock the test controls, so `updatedAt` ordering is deterministic. */
class FakeTimeProvider(
    var now: Long = Fixtures.NOW_MS,
    var elapsed: Long = 0L,
) : TimeProvider {
    override var serverClockOffsetMs: Long = 0L

    override fun nowMs(): Long = now

    override fun elapsedRealtimeMs(): Long = elapsed

    fun advance(byMs: Long) {
        now += byMs
        elapsed += byMs
    }
}
