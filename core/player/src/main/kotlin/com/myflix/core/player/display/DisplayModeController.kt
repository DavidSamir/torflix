package com.myflix.core.player.display

import android.app.Activity
import com.myflix.core.common.log.MyflixLog
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DisplayMode"

/**
 * Bridges the player (which knows the content frame rate) to the activity (the only thing that can
 * request a display mode).
 *
 * The activity registers itself while resumed and is held weakly, so a destroyed activity can never
 * be leaked by the singleton player stack.
 */
@Singleton
class DisplayModeController @Inject constructor(
    private val frameRateMatcher: FrameRateMatcher,
) {

    private var activityRef: WeakReference<Activity>? = null
    private var applied = false

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) {
            reset()
            activityRef = null
        }
    }

    /**
     * Requests a matching refresh rate. Returns true when a switch was actually started, so the UI
     * can hold the screen black until the TV has resynced (plan.md §7.3).
     */
    fun applyForContent(contentFrameRate: Float?, enabled: Boolean): Boolean {
        if (!enabled) return false
        val activity = activityRef?.get() ?: return false
        return runCatching { frameRateMatcher.matchFrameRate(activity, contentFrameRate) }
            .onFailure { MyflixLog.w(TAG, "Frame rate matching failed", it) }
            .getOrDefault(false)
            .also { applied = applied || it }
    }

    /** Restores the system display mode; safe to call when nothing was changed. */
    fun reset() {
        val activity = activityRef?.get() ?: return
        if (!applied) return
        runCatching { frameRateMatcher.reset(activity) }
            .onFailure { MyflixLog.w(TAG, "Display mode reset failed", it) }
        applied = false
    }
}
