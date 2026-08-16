package com.torfilx.core.common.time

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Wall-clock and monotonic time, injected so tests can control both. */
interface TimeProvider {
    fun nowMs(): Long
    fun elapsedRealtimeMs(): Long

    /** Timestamp stamped on locally-originated writes (progress, My List, search history). */
    fun writeTimestampMs(): Long = nowMs()
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
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
