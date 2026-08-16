package com.myflix.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.myflix.core.common.log.MyflixLog
import com.myflix.core.ui.theme.FOCUS_ANIMATION_MS
import com.myflix.core.ui.theme.MyflixColors

/**
 * The one button used everywhere.
 *
 * Built directly on `focusable` + `clickable` rather than a Material button so the focus visuals are
 * identical to the cards: a scale-up plus a solid border, never a colour-only change (plan.md §4).
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = tween(FOCUS_ANIMATION_MS),
        label = "buttonScale",
    )
    val requester = focusRequester ?: remember { FocusRequester() }

    LaunchedEffect(autoFocus, enabled) {
        if (autoFocus && enabled) {
            runCatching { requester.requestFocus() }
                .onFailure { MyflixLog.w("TvButton", "autoFocus failed for '$text'") }
        }
    }

    val background = when {
        !enabled -> MyflixColors.SurfaceLow
        isFocused && primary -> MyflixColors.TextPrimary
        isFocused -> MyflixColors.SurfaceHighest
        primary -> MyflixColors.Accent
        else -> MyflixColors.SurfaceHigh
    }
    val contentColor = when {
        !enabled -> MyflixColors.TextTertiary
        isFocused && primary -> MyflixColors.Background
        else -> MyflixColors.TextPrimary
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) MyflixColors.Focus else MyflixColors.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .focusRequester(requester)
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leadingIcon?.invoke()
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Selectable chip used for sort/filter and season selection. */
@Composable
fun TvChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val background = when {
        isFocused -> MyflixColors.TextPrimary
        selected -> MyflixColors.SurfaceHighest
        else -> MyflixColors.SurfaceLow
    }
    val contentColor = if (isFocused) MyflixColors.Background else MyflixColors.TextPrimary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(
                width = if (isFocused) 2.dp else if (selected) 1.dp else 0.dp,
                color = when {
                    isFocused -> MyflixColors.Focus
                    selected -> MyflixColors.TextSecondary
                    else -> MyflixColors.Transparent
                },
                shape = RoundedCornerShape(20.dp),
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}
