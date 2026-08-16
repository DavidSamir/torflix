package com.torfilx.core.player.display

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.Window
import android.view.WindowManager
import com.torfilx.core.common.log.TorfilxLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "FrameRate"

/**
 * Switches the HDMI output mode to match the content frame rate.
 *
 * Without this, 23.976 fps film played at 60 Hz judders every fifth frame — the single most visible
 * quality problem on a Fire TV. The mode is chosen before playback starts and reset on exit; it is
 * never changed mid-playback, which would black the screen for a second (plan.md §7.3).
 */
@Singleton
class FrameRateMatcher @Inject constructor() {

    private var originalModeId: Int? = null

    /**
     * Returns true when a mode change was requested. The caller should keep the screen black for a
     * moment afterwards, because the TV resyncs HDMI and shows nothing meanwhile.
     */
    fun matchFrameRate(activity: Activity, contentFrameRate: Float?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (contentFrameRate == null || contentFrameRate <= 0f) return false

        val window = activity.window ?: return false
        val display = displayOf(activity) ?: return false
        val modes = runCatching { display.supportedModes.toList() }.getOrDefault(emptyList())
        if (modes.size <= 1) return false

        val currentMode = runCatching { display.mode }.getOrNull() ?: return false
        if (originalModeId == null) originalModeId = currentMode.modeId

        val target = bestMode(modes, currentMode, contentFrameRate) ?: return false
        if (target.modeId == currentMode.modeId) {
            TorfilxLog.d(TAG, "Display already at ${currentMode.refreshRate} Hz")
            return false
        }

        applyMode(window, target.modeId)
        TorfilxLog.i(
            TAG,
            "Switched display to ${target.physicalWidth}x${target.physicalHeight}@${target.refreshRate} " +
                "for ${contentFrameRate}fps content",
        )
        return true
    }

    /** Restores the mode the system was in before playback. */
    fun reset(activity: Activity) {
        val window = activity.window ?: return
        val original = originalModeId ?: return
        applyMode(window, original)
        originalModeId = null
        TorfilxLog.d(TAG, "Display mode restored")
    }

    private fun applyMode(window: Window, modeId: Int) {
        runCatching {
            val attributes: WindowManager.LayoutParams = window.attributes
            attributes.preferredDisplayModeId = modeId
            window.attributes = attributes
        }.onFailure { TorfilxLog.w(TAG, "Could not set display mode", it) }
    }

    private fun displayOf(activity: Activity): Display? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
    }.getOrNull()

    /**
     * Picks the best mode at the *current resolution*.
     *
     * Resolution is deliberately never changed: the UI is composed for the current output size, and
     * dropping from 4K to 1080p to gain a refresh rate would look worse than the judder it fixes.
     */
    internal fun bestMode(
        modes: List<Display.Mode>,
        currentMode: Display.Mode,
        contentFrameRate: Float,
    ): Display.Mode? {
        val sameResolution = modes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
        if (sameResolution.isEmpty()) return null

        return sameResolution.minByOrNull { mode -> penalty(mode.refreshRate, contentFrameRate) }
            ?.takeIf { penalty(it.refreshRate, contentFrameRate) < MAX_ACCEPTABLE_PENALTY }
    }

    /**
     * How badly a refresh rate suits a content rate.
     *
     * An exact multiple is perfect (24→48/72, 25→50, 30→60); a non-integer ratio means periodic
     * frame repetition, so the penalty grows with the fractional part.
     */
    internal fun penalty(refreshRate: Float, contentFrameRate: Float): Float {
        if (refreshRate <= 0f || contentFrameRate <= 0f) return Float.MAX_VALUE
        val ratio = refreshRate / contentFrameRate
        if (ratio < 0.99f) return Float.MAX_VALUE // never below the content rate
        val nearestMultiple = ratio.roundToNearest()
        val error = abs(ratio - nearestMultiple)
        // Prefer lower refresh rates among equally good candidates: 24 Hz beats 72 Hz for 24p.
        return error * 100f + nearestMultiple * 0.01f
    }

    private fun Float.roundToNearest(): Float = kotlin.math.round(this)

    private companion object {
        /** Above this, the mode is worse than staying put. */
        const val MAX_ACCEPTABLE_PENALTY = 1.0f
    }
}
