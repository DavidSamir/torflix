package com.torfilx.tv

import android.app.Application
import android.content.Context
import android.content.ComponentCallbacks2
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.bitmapConfig
import coil3.request.crossfade
import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.catalog.BundledCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
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

    @Inject
    lateinit var catalog: BundledCatalog

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        TorfilxLog.debugEnabled = BuildConfig.DEBUG
        TorfilxLog.i(TAG, "TORFILX ${BuildConfig.VERSION_NAME} starting")

        // Installed first: a failure anywhere after this point restarts the app instead of showing
        // the system.s "keeps stopping" dialog on the TV.
        installCrashGuard()

        // Parse and index the catalogue before the first screen asks for it, off the main thread:
        // with a few thousand entries this is tens of milliseconds that must not land on a frame.
        applicationScope.launch { catalog.preload() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Posters decode as RGB_565: half the memory of ARGB_8888, and the banding is invisible
            // on a TV. On a 1.5 GB stick that is the difference between a smooth row and a GC pause
            // every few cards.
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, memoryCachePercent())
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

    /** Low-RAM devices (1.5 GB Fire Sticks) get a smaller share of a much smaller heap. */
    private fun memoryCachePercent(): Double {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val lowRam = activityManager?.isLowRamDevice == true ||
            (activityManager?.memoryClass ?: 0) <= LOW_MEMORY_CLASS_MB
        return if (lowRam) LOW_RAM_CACHE_PERCENT else MEMORY_CACHE_PERCENT
    }

    private companion object {
        const val MEMORY_CACHE_PERCENT = 0.25
        const val LOW_RAM_CACHE_PERCENT = 0.15
        const val LOW_MEMORY_CLASS_MB = 128
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024
    }
}
