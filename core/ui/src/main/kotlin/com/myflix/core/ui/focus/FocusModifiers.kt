package com.myflix.core.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import com.myflix.core.common.log.MyflixLog

private const val TAG = "Focus"

/**
 * Requests focus once, and only once the content is actually there.
 *
 * Requesting focus while a screen is still in its Loading state silently fails — the node is not
 * attached yet — which is the classic cause of "the remote does nothing" on a TV app. Gating on
 * [enabled] (set when content arrives) is what makes initial focus reliable (plan.md §5.2 rule 1).
 */
@Composable
fun rememberInitialFocusRequester(enabled: Boolean): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        if (enabled) {
            runCatching { requester.requestFocus() }
                .onFailure { MyflixLog.w(TAG, "Initial focus request failed: ${it.message}") }
        }
    }
    return requester
}

/** Applies [requester] and marks the node focusable, in the order Compose requires. */
fun Modifier.initialFocus(requester: FocusRequester): Modifier =
    this.focusRequester(requester).focusable()

/** Test tag helper so instrumented focus tests can address a node without a string literal soup. */
fun Modifier.tvTestTag(tag: String): Modifier = this.testTag(tag)

/**
 * Chooses where focus should move when the currently focused item disappears from a list.
 *
 * Rule (plan.md §5.2 rule 3): prefer the next sibling, then the previous one, and only give up when
 * the list is empty — never leave focus on nothing, which makes the remote appear dead.
 */
fun <T> nextFocusTargetAfterRemoval(
    itemsBefore: List<T>,
    removedIndex: Int,
): Int? {
    val remaining = itemsBefore.size - 1
    if (remaining <= 0) return null
    return when {
        removedIndex < remaining -> removedIndex // the item that shifted into this slot
        else -> remaining - 1 // removed the last item: fall back to the new last one
    }
}
