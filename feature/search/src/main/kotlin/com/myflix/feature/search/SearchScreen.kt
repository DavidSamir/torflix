package com.myflix.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.myflix.core.ui.component.KeyboardLayout
import com.myflix.core.ui.component.OnScreenKeyboard
import com.myflix.core.ui.component.PosterCard
import com.myflix.core.ui.component.SearchField
import com.myflix.core.ui.component.TvChip
import com.myflix.core.ui.theme.LocalMyflixDimens
import com.myflix.core.ui.theme.MyflixColors

/**
 * Search: on-screen keyboard on the left, live results on the right.
 *
 * The system IME is deliberately not used — on Fire TV it is an overlay that grabs focus and fights
 * with Compose focus traversal (plan.md §6.5).
 */
@Composable
fun SearchScreen(
    onOpenDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dimens = LocalMyflixDimens.current
    var layout by remember { mutableStateOf(KeyboardLayout.LATIN) }
    val firstKeyFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstKeyFocus.requestFocus() }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MyflixColors.Background)
            .padding(horizontal = dimens.overscanHorizontal, vertical = dimens.overscanVertical),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(
            modifier = Modifier.width(380.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SearchField(
                value = state.query,
                placeholder = "Search films and shows",
            )
            OnScreenKeyboard(
                onCharacter = viewModel::appendCharacter,
                onBackspace = viewModel::backspace,
                onClear = viewModel::clear,
                layout = layout,
                onLayoutChange = { layout = it },
                firstKeyFocusRequester = firstKeyFocus,
            )
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                state.errorMessage != null -> CenteredMessage(
                    title = "Search unavailable",
                    message = state.errorMessage!!,
                )

                state.showRecent && state.recentSearches.isNotEmpty() -> RecentSearches(
                    recent = state.recentSearches,
                    onPick = viewModel::setQuery,
                    onClear = viewModel::clearHistory,
                )

                state.showRecent -> CenteredMessage(
                    title = "Search your library",
                    message = "Type at least two letters using the keyboard on the left.",
                )

                state.showNoResults -> CenteredMessage(
                    title = "No results for “${state.query}”",
                    message = "Check the spelling, or try part of the title.",
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(RESULT_COLUMNS),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusGroup()
                        .focusRestorer(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
                    verticalArrangement = Arrangement.spacedBy(dimens.cardSpacing + 16.dp),
                ) {
                    items(
                        items = state.results,
                        key = { result -> result.card.playableId },
                        contentType = { "poster" },
                    ) { result ->
                        PosterCard(
                            card = result.card,
                            onClick = { onOpenDetails(result.card.item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSearches(
    recent: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Recent searches",
            style = MaterialTheme.typography.titleLarge,
            color = MyflixColors.TextPrimary,
        )
        recent.forEach { entry ->
            TvChip(text = entry, selected = false, onClick = { onPick(entry) })
        }
        TvChip(text = "Clear history", selected = false, onClick = onClear)
    }
}

@Composable
private fun CenteredMessage(title: String, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MyflixColors.TextPrimary,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MyflixColors.TextSecondary,
            )
        }
    }
}

private const val RESULT_COLUMNS = 4
