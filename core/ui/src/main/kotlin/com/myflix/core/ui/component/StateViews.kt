package com.myflix.core.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.myflix.core.ui.theme.LocalMyflixDimens
import com.myflix.core.ui.theme.MyflixColors

/**
 * Skeleton placeholder.
 *
 * Skeletons are sized exactly like the real cards so that vertical D-pad navigation is stable while
 * a row loads and nothing jumps when content arrives (plan.md §5.2 rule 4).
 */
@Composable
fun SkeletonBox(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(LocalMyflixDimens.current.cornerRadius))
            .alpha(alpha)
            .background(MyflixColors.SurfaceHigh),
    )
}

/** A whole row of skeletons, used while Home is loading. */
@Composable
fun SkeletonRow(
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    count: Int = 6,
) {
    val dimens = LocalMyflixDimens.current
    Column(modifier = modifier.fillMaxWidth()) {
        SkeletonBox(width = 180.dp, height = 24.dp, modifier = Modifier.padding(horizontal = dimens.overscanHorizontal))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(start = dimens.overscanHorizontal, top = 12.dp, bottom = 8.dp)),
            horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
        ) {
            repeat(count) {
                SkeletonBox(
                    width = if (landscape) dimens.landscapeWidth else dimens.posterWidth,
                    height = if (landscape) dimens.landscapeHeight else dimens.posterHeight,
                )
            }
        }
    }
}

/**
 * Full-screen error. Every error state carries an action, because a TV user cannot pull-to-refresh
 * or open a browser to work around it (plan.md §10).
 */
@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MyflixColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(48.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MyflixColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MyflixColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (primaryActionLabel != null && onPrimaryAction != null) {
                    TvButton(text = primaryActionLabel, onClick = onPrimaryAction, autoFocus = true)
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    TvButton(text = secondaryActionLabel, onClick = onSecondaryAction, primary = false)
                }
            }
        }
    }
}

/** Empty state: same shape as [ErrorState] but phrased as guidance rather than failure. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ErrorState(
        title = title,
        message = message,
        modifier = modifier,
        primaryActionLabel = actionLabel,
        onPrimaryAction = onAction,
    )
}

/** Compact inline failure used for a single row, so one bad row never blanks the screen. */
@Composable
fun InlineRowError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalMyflixDimens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.overscanHorizontal, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MyflixColors.TextSecondary,
        )
        TvButton(text = "Retry", onClick = onRetry, primary = false)
    }
}
