package com.torfilx.tv

import android.app.Application
import android.content.ComponentCallbacks2
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.torfilx.core.common.log.TorfilxLog
import dagger.hilt.android.HiltAndroidApp
import okio.Path.Companion.toOkioPath

private const val TAG = "App"

/**
 * Application entry point.
 *
 * Image cache sizes are chosen for a 1.5 GB Fire Stick: 25% of the heap in memory and 256 MB on
 * disk, with `RGB_565`-friendly inexact sizing done at the call sites (plan.md §4, §9).
 */
@HiltAndroidApp
class TorfilxApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        TorfilxLog.debugEnabled = BuildConfig.DEBUG
        TorfilxLog.i(TAG, "TORFILX ${BuildConfig.VERSION_NAME} starting")
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Under memory pressure the image cache goes first; the player is never touched here.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            TorfilxLog.i(TAG, "Trimming image memory cache (level=$level)")
            SingletonImageLoader.get(this).memoryCache?.clear()
        }
    }

    private companion object {
        const val MEMORY_CACHE_PERCENT = 0.25
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024
    }
}
