package com.torfilx.core.ui.component

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.model.MediaCard
import com.torfilx.core.ui.focus.keepNeighbourComposed
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors

/**
 * A Netflix-style horizontal row.
 *
 * Three things make it feel right on a remote (plan.md §5.2):
 * - `focusRestorer()` + stable `key`s, so returning from Details lands on the card you left;
 * - `focusGroup()`, so vertical navigation treats the row as one unit;
 * - `contentPadding` equal to the overscan inset, so the first card is not clipped by the TV bezel
 *   and there is room for the focus scale-up to breathe.
 */
@Composable
fun MediaRow(
    title: String,
    items: List<MediaCard>,
    onCardClick: (MediaCard) -> Unit,
    modifier: Modifier = Modifier,
    onCardLongClick: ((MediaCard) -> Unit)? = null,
    landscape: Boolean = false,
    headerTrailing: @Composable (() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val dimens = LocalTorfilxDimens.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth()) {
        RowHeader(title = title, trailing = headerTrailing)
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
            contentPadding = PaddingValues(
                start = dimens.overscanHorizontal,
                end = dimens.overscanHorizontal,
                // Vertical padding leaves room for the focused card's scale-up and its label.
                top = 8.dp,
                bottom = 8.dp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .focusRestorer(),
        ) {
            items(
                count = items.size,
                key = { index -> items[index].playableId },
                contentType = { if (landscape) "landscape" else "poster" },
            ) { index ->
                val card = items[index]
                // Without this the row stops at the last card the lazy layout happened to compose,
                // which on a 1080p screen is about five of them — a 217-title genre row looked like
                // the handful at its front, with nothing on screen to suggest otherwise.
                val focusNudge = Modifier.keepNeighbourComposed(
                    index = index,
                    itemCount = items.size,
                    state = listState,
                    scope = scope,
                )
                if (landscape) {
                    LandscapeCard(
                        card = card,
                        onClick = { onCardClick(card) },
                        onLongClick = onCardLongClick?.let { handler -> { handler(card) } },
                        modifier = focusNudge,
                    )
                } else {
                    PosterCard(
                        card = card,
                        onClick = { onCardClick(card) },
                        onLongClick = onCardLongClick?.let { handler -> { handler(card) } },
                        modifier = focusNudge,
                    )
                }
            }
        }
    }
}

@Composable
fun RowHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val dimens = LocalTorfilxDimens.current
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.overscanHorizontal),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = TorfilxColors.TextPrimary,
        )
        trailing?.invoke()
    }
}
