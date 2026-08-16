package com.myflix.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.myflix.core.ui.theme.MyflixColors

/** Which character set the on-screen keyboard is showing. */
enum class KeyboardLayout(val label: String) {
    LATIN("ABC"),
    HEBREW("אבג"),
    NUMBERS("123"),
}

private val LATIN_ROWS = listOf(
    "ABCDEF",
    "GHIJKL",
    "MNOPQR",
    "STUVWX",
    "YZ'-",
)

private val HEBREW_ROWS = listOf(
    "אבגדהו",
    "זחטיכל",
    "מנסעפצ",
    "קרשת",
)

private val NUMBER_ROWS = listOf(
    "1234567890",
    ".,:!?&",
)

/**
 * A grid keyboard driven entirely by the D-pad.
 *
 * The Fire TV system IME is an overlay that steals focus and behaves unpredictably inside Compose,
 * so search and text entry use this instead — the same choice Netflix makes (plan.md §6.5).
 */
@Composable
fun OnScreenKeyboard(
    onCharacter: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    layout: KeyboardLayout = KeyboardLayout.LATIN,
    onLayoutChange: (KeyboardLayout) -> Unit = {},
    firstKeyFocusRequester: FocusRequester? = null,
) {
    val rows = when (layout) {
        KeyboardLayout.LATIN -> LATIN_ROWS
        KeyboardLayout.HEBREW -> HEBREW_ROWS
        KeyboardLayout.NUMBERS -> NUMBER_ROWS
    }

    Column(
        modifier = modifier.focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEachIndexed { rowIndex, rowChars ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowChars.forEachIndexed { charIndex, character ->
                    KeyboardKey(
                        label = character.toString(),
                        onClick = { onCharacter(character) },
                        focusRequester = if (rowIndex == 0 && charIndex == 0) {
                            firstKeyFocusRequester
                        } else {
                            null
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyboardKey(label = "space", width = 108.dp, onClick = { onCharacter(' ') })
            KeyboardKey(label = "⌫", onClick = onBackspace, description = "Backspace")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyboardLayout.entries.forEach { candidate ->
                KeyboardKey(
                    label = candidate.label,
                    width = 68.dp,
                    selected = candidate == layout,
                    onClick = { onLayoutChange(candidate) },
                )
            }
        }
        Row {
            KeyboardKey(label = "clear", width = 108.dp, onClick = onClear)
        }
    }
}

@Composable
private fun KeyboardKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 48.dp,
    selected: Boolean = false,
    description: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .width(width)
            .size(width = width, height = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isFocused -> MyflixColors.TextPrimary
                    selected -> MyflixColors.SurfaceHighest
                    else -> MyflixColors.SurfaceLow
                },
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) MyflixColors.Focus else MyflixColors.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics { contentDescription = description ?: label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFocused) MyflixColors.Background else MyflixColors.TextPrimary,
        )
    }
}

/** The text field that the on-screen keyboard types into. */
@Composable
fun SearchField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MyflixColors.SurfaceLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = value.ifEmpty { placeholder },
            style = MaterialTheme.typography.titleMedium,
            color = if (value.isEmpty()) MyflixColors.TextTertiary else MyflixColors.TextPrimary,
            maxLines = 1,
        )
    }
}
