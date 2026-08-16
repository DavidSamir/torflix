package com.torfilx.core.common.time

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wall-clock and monotonic time, injected so tests can control both.
 *
 * [nowMs] is corrected by [serverClockOffsetMs] before it is written to any `updatedAt` field, so a
 * Fire TV with a wrong clock cannot win a sync conflict against the server (plan.md §10).
 */
interface TimeProvider {
    fun nowMs(): Long
    fun elapsedRealtimeMs(): Long
    var serverClockOffsetMs: Long

    /** Timestamp to stamp on locally-originated writes. */
    fun serverAdjustedNowMs(): Long = nowMs() + serverClockOffsetMs
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    @Volatile
    override var serverClockOffsetMs: Long = 0L

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TimeModule {
    @Binds
    @Singleton
    abstract fun bindsTimeProvider(impl: SystemTimeProvider): TimeProvider
}
