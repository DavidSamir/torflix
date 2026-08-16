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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.myflix.core.model.MediaCard
import com.myflix.core.ui.focus.onMenuKey
import com.myflix.core.ui.image.Artwork
import com.myflix.core.ui.theme.FOCUS_ANIMATION_MS
import com.myflix.core.ui.theme.FOCUS_SCALE
import com.myflix.core.ui.theme.LocalMyflixDimens
import com.myflix.core.ui.theme.MyflixColors
import com.myflix.core.ui.util.Format

/**
 * The poster card used in every row and grid.
 *
 * Focus behaviour is the whole point (plan.md §4, §5): a scale-up plus a high-contrast border that
 * appears immediately, never colour alone, and an accessible description that says what the card is
 * and how far it has been watched (VoiceView reads this aloud on Fire TV).
 */
@Composable
fun PosterCard(
    card: MediaCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val dimens = LocalMyflixDimens.current
    FocusableMediaCard(
        card = card,
        onClick = onClick,
        onLongClick = onLongClick,
        widthDp = dimens.posterWidth,
        heightDp = dimens.posterHeight,
        artworkUrl = card.item.images.poster,
        modifier = modifier,
        interactionSource = interactionSource,
    )
}

/** 16:9 card used for Continue Watching and episodes. */
@Composable
fun LandscapeCard(
    card: MediaCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val dimens = LocalMyflixDimens.current
    FocusableMediaCard(
        card = card,
        onClick = onClick,
        onLongClick = onLongClick,
        widthDp = dimens.landscapeWidth,
        heightDp = dimens.landscapeHeight,
        artworkUrl = card.episode?.thumbUrl
            ?: card.item.images.thumb
            ?: card.item.images.backdrop
            ?: card.item.images.poster,
        modifier = modifier,
        interactionSource = interactionSource,
    )
}

@Composable
private fun FocusableMediaCard(
    card: MediaCard,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    val dimens = LocalMyflixDimens.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) FOCUS_SCALE else 1f,
        animationSpec = tween(FOCUS_ANIMATION_MS),
        label = "cardScale",
    )

    val shape = RoundedCornerShape(dimens.cornerRadius)
    val description = remember(card, isFocused) { card.accessibilityDescription() }

    Column(modifier = modifier.width(widthDp)) {
        Box(
            modifier = Modifier
                .width(widthDp)
                .height(heightDp)
                .scale(scale)
                .clip(shape)
                .background(MyflixColors.SurfaceLow)
                .border(
                    width = if (isFocused) dimens.focusBorderWidth else 0.dp,
                    color = if (isFocused) MyflixColors.Focus else MyflixColors.Transparent,
                    shape = shape,
                )
                .focusable(interactionSource = source)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    onClick = onClick,
                )
                // The Fire TV remote's Menu key is the TV equivalent of a long press: it opens the
                // contextual actions for the focused card (plan.md §5.1).
                .onMenuKey { onLongClick?.invoke() }
                .semantics { contentDescription = description },
        ) {
            Artwork(
                url = artworkUrl,
                title = card.item.title,
                seed = card.item.id,
                widthDp = widthDp,
                heightDp = heightDp,
                modifier = Modifier.fillMaxSize(),
            )

            card.progress?.takeIf { it.fraction > 0f && !it.watched }?.let { progress ->
                CardProgressBar(
                    fraction = progress.fraction,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }

            if (card.progress?.watched == true) {
                WatchedBadge(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }

        // The label sits under the card and only renders for the focused item, which keeps the row
        // visually calm and avoids laying out text for off-screen cards.
        if (isFocused) {
            CardLabel(card = card, widthDp = widthDp)
        }
    }
}

@Composable
private fun CardLabel(card: MediaCard, widthDp: androidx.compose.ui.unit.Dp) {
    Column(
        modifier = Modifier
            .width(widthDp)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = card.item.title,
            style = MaterialTheme.typography.labelLarge,
            color = MyflixColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = card.subtitle()
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MyflixColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun CardProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(4.dp)
            .background(
                Brush.verticalGradient(
                    listOf(MyflixColors.Transparent, MyflixColors.ScrimSoft),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(MyflixColors.ProgressFill),
        )
    }
}

@Composable
private fun WatchedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MyflixColors.ScrimStrong),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.labelMedium,
            color = MyflixColors.TextPrimary,
        )
    }
}

/** Secondary line under a focused card: episode code and remaining time when relevant. */
private fun MediaCard.subtitle(): String {
    val parts = buildList {
        episode?.let { add(Format.episodeCode(it)) }
        progress?.takeIf { it.fraction > 0f && !it.watched }?.let { p ->
            val remaining = Format.runtime(p.remainingMs)
            if (remaining.isNotEmpty()) add("$remaining left")
        }
        if (isEmpty()) {
            item.year?.let { add(it.toString()) }
            Format.runtime(item.runtimeMs).takeIf { it.isNotEmpty() }?.let { add(it) }
        }
    }
    return parts.joinToString(" · ")
}

private fun MediaCard.accessibilityDescription(): String = buildString {
    append(item.title)
    episode?.let { append(", ${Format.episodeCode(it)}${it.title?.let { t -> ", $t" }.orEmpty()}") }
    item.year?.let { append(", $it") }
    progress?.let { p ->
        if (p.watched) {
            append(", watched")
        } else if (p.fraction > 0f) {
            append(", ${Format.percentComplete(p.positionMs, p.durationMs)} percent watched")
        }
    }
    if (inMyList) append(", in My List")
}
