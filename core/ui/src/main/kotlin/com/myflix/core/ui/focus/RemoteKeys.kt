package com.myflix.core.ui.focus

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Fire TV remote key handling.
 *
 * Keys are matched by key code rather than by Compose's semantic helpers because the Fire TV remote
 * and Bluetooth game controllers report several different codes for the same physical button
 * (plan.md §5.1).
 */
object RemoteKeys {
    val MENU = Key(AndroidKeyEvent.KEYCODE_MENU)
    val BACK = Key(AndroidKeyEvent.KEYCODE_BACK)
    val CENTER = Key(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
    val ENTER = Key(AndroidKeyEvent.KEYCODE_ENTER)
    val LEFT = Key(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
    val RIGHT = Key(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
    val UP = Key(AndroidKeyEvent.KEYCODE_DPAD_UP)
    val DOWN = Key(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
    val PLAY_PAUSE = Key(AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    val PLAY = Key(AndroidKeyEvent.KEYCODE_MEDIA_PLAY)
    val PAUSE = Key(AndroidKeyEvent.KEYCODE_MEDIA_PAUSE)
    val FAST_FORWARD = Key(AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
    val REWIND = Key(AndroidKeyEvent.KEYCODE_MEDIA_REWIND)
    val NEXT = Key(AndroidKeyEvent.KEYCODE_MEDIA_NEXT)
    val PREVIOUS = Key(AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS)
    val STOP = Key(AndroidKeyEvent.KEYCODE_MEDIA_STOP)
    val INFO = Key(AndroidKeyEvent.KEYCODE_INFO)

    val CONFIRM_KEYS = setOf(CENTER, ENTER, Key(AndroidKeyEvent.KEYCODE_BUTTON_A))
    val DIRECTION_KEYS = setOf(LEFT, RIGHT, UP, DOWN)
}

/** Invokes [onMenu] when the remote's Menu key is pressed while this node has focus. */
fun Modifier.onMenuKey(onMenu: () -> Unit): Modifier = this.onKeyEvent { event ->
    if (event.type == KeyEventType.KeyUp && event.key == RemoteKeys.MENU) {
        onMenu()
        true
    } else {
        false
    }
}

/**
 * Handles a set of keys before focus traversal sees them.
 *
 * Used by the player, where ←/→ must seek instead of moving focus; [handler] returns true when it
 * consumed the key.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onRemoteKey(
    handler: (key: Key, isKeyDown: Boolean, repeatCount: Int) -> Boolean,
): Modifier = this.onPreviewKeyEvent { event ->
    val isDown = event.type == KeyEventType.KeyDown
    val repeat = event.nativeKeyEvent?.repeatCount ?: 0
    handler(event.key, isDown, repeat)
}

private val androidx.compose.ui.input.key.KeyEvent.nativeKeyEvent: AndroidKeyEvent?
    get() = runCatching { this.nativeKeyEvent as? AndroidKeyEvent }.getOrNull()

/** Convenience: the Android key code behind a Compose [Key]. */
val Key.androidKeyCode: Int get() = this.nativeKeyCode
