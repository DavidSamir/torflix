package com.torfilx.core.testing

import com.torfilx.core.common.time.TimeProvider

/** A clock the test controls, so `updatedAt` ordering is deterministic. */
class FakeTimeProvider(
    var now: Long = Fixtures.NOW_MS,
    var elapsed: Long = 0L,
) : TimeProvider {

    override fun nowMs(): Long = now

    override fun elapsedRealtimeMs(): Long = elapsed

    fun advance(byMs: Long) {
        now += byMs
        elapsed += byMs
    }
}
