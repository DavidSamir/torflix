package com.myflix.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.myflix.core.ui.theme.LocalMyflixDimens
import com.myflix.core.ui.theme.MyflixColors

/** A destination in the top navigation bar. */
data class TopNavItem(
    val id: String,
    val label: String,
)

/**
 * Top tab bar.
 *
 * Selection follows focus only on OK, not on focus: moving across the tabs must not tear down the
 * screen below and destroy the row you were about to come back to (plan.md §5.2 rule 6).
 */
@Composable
fun TopNavBar(
    items: List<TopNavItem>,
    selectedId: String,
    onSelect: (TopNavItem) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val dimens = LocalMyflixDimens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MyflixColors.Background)
            .padding(horizontal = dimens.overscanHorizontal)
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "MYFLIX",
            style = MaterialTheme.typography.titleLarge,
            color = MyflixColors.Accent,
            modifier = Modifier.padding(end = 24.dp),
        )
        items.forEach { item ->
            NavTab(
                item = item,
                selected = item.id == selectedId,
                onClick = { onSelect(item) },
            )
        }
        Box(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun NavTab(
    item: TopNavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val underlineAlpha by animateFloatAsState(
        targetValue = if (selected || isFocused) 1f else 0f,
        animationSpec = tween(160),
        label = "tabUnderline",
    )

    Box(
        modifier = Modifier
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = item.label
            },
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                isFocused -> MyflixColors.TextPrimary
                selected -> MyflixColors.TextPrimary
                else -> MyflixColors.TextSecondary
            },
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(3.dp)
                .alpha(underlineAlpha)
                .background(if (isFocused) MyflixColors.Focus else MyflixColors.Accent),
        )
    }
}
