package com.torfilx.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.model.LibrarySort
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.WatchedFilter
import com.torfilx.core.ui.component.EmptyState
import com.torfilx.core.ui.component.PosterCard
import com.torfilx.core.ui.component.SkeletonRow
import com.torfilx.core.ui.component.TvChip
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Browse grid for Movies, Shows and My List.
 *
 * The whole library is cached locally (a personal library is thousands of rows, not millions), so
 * the grid reads straight from Room: sorting and filtering are instant and work offline. Paging is
 * only used when *fetching* from the server (plan.md §6.2, §8.2).
 */
@Composable
fun LibraryScreen(
    mode: LibraryMode,
    onOpenDetails: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(mode) { viewModel.setMode(mode) }

    // Back from a tab returns to Home rather than exiting the app.
    androidx.activity.compose.BackHandler(enabled = true) { onBack() }

    val dimens = LocalTorfilxDimens.current
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize().background(TorfilxColors.Background)) {
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.Warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TorfilxColors.SurfaceHigh)
                    .padding(horizontal = dimens.overscanHorizontal, vertical = 6.dp),
            )
        }

        FilterBar(
            state = state,
            onSort = viewModel::setSort,
            onGenre = viewModel::setGenre,
            onWatched = viewModel::setWatchedFilter,
        )

        when {
            state.isLoading -> Column(
                Modifier.fillMaxSize().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(dimens.rowSpacing),
            ) {
                SkeletonRow(count = 6)
                SkeletonRow(count = 6)
            }

            state.isEmpty -> EmptyState(
                title = when (state.mode) {
                    LibraryMode.MY_LIST -> "My List is empty"
                    LibraryMode.MOVIES -> "No movies match these filters"
                },
                message = when (state.mode) {
                    LibraryMode.MY_LIST -> "Press the Menu button on any title to add it here."
                    else -> "Try clearing the genre or watched filter."
                },
                actionLabel = "Refresh",
                onAction = viewModel::refresh,
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusGroup()
                    .focusRestorer(),
                contentPadding = PaddingValues(
                    start = dimens.overscanHorizontal,
                    end = dimens.overscanHorizontal,
                    top = 12.dp,
                    bottom = dimens.overscanVertical * 2,
                ),
                horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
                verticalArrangement = Arrangement.spacedBy(dimens.cardSpacing + 16.dp),
            ) {
                items(
                    count = state.cards.size,
                    key = { index -> state.cards[index].item.id },
                    contentType = { "poster" },
                ) { index ->
                    val card: MediaCard = state.cards[index]
                    PosterCard(
                        card = card,
                        onClick = { onOpenDetails(card.item.id) },
                        onLongClick = { viewModel.toggleMyList(card) },
                        modifier = Modifier.keepNextRowComposed(
                            index = index,
                            itemCount = state.cards.size,
                            gridState = gridState,
                            scope = scope,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterBar(
    state: LibraryUiState,
    onSort: (LibrarySort) -> Unit,
    onGenre: (String?) -> Unit,
    onWatched: (WatchedFilter) -> Unit,
) {
    val dimens = LocalTorfilxDimens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The total is on screen on purpose. A grid that stops scrolling looks exactly like a small
        // catalogue, and that ambiguity is what made "some of the movies are missing" impossible to
        // diagnose from the sofa. With a count, a truncated view is obvious at a glance.
        if (!state.isLoading) {
            Text(
                text = when (state.cards.size) {
                    1 -> "1 title"
                    else -> "${state.cards.size} titles"
                },
                style = MaterialTheme.typography.labelMedium,
                color = TorfilxColors.TextSecondary,
                modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            items(LibrarySort.entries.toList(), key = { it.name }) { sort ->
                TvChip(
                    text = sort.label(),
                    selected = state.query.sort == sort,
                    onClick = { onSort(sort) },
                )
            }
            item(key = "watched-filter") {
                TvChip(
                    text = state.query.watched.label(),
                    selected = state.query.watched != WatchedFilter.ALL,
                    onClick = { onWatched(state.query.watched.next()) },
                )
            }
        }

        if (state.genres.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.focusRestorer(),
            ) {
                item(key = "genre-all") {
                    TvChip(
                        text = "All genres",
                        selected = state.query.genre == null,
                        onClick = { onGenre(null) },
                    )
                }
                items(state.genres, key = { it }) { genre ->
                    TvChip(
                        text = genre,
                        selected = state.query.genre == genre,
                        onClick = { onGenre(if (state.query.genre == genre) null else genre) },
                    )
                }
            }
        }
    }
}

private const val GRID_COLUMNS = 6

/**
 * Guarantees there is always a composed row past the focused one, in both directions.
 *
 * D-pad navigation in a lazy grid can only move focus to a node that **exists**. A lazy layout
 * composes roughly what is on screen, so when focus reaches the last visible row there is often
 * nothing below it to move to, focus search fails, and the grid stops dead — with 6 columns and four
 * visible rows that is about 25 titles, which is exactly what a full 2000-title catalogue looked
 * like on the TV.
 *
 * Nudging the scroll by one row whenever focus lands on the first or last visible row means the
 * neighbour is always composed before it is needed. It leaves the middle of the grid alone, so it
 * does not fight Compose's own bring-into-view behaviour or make the grid feel jumpy.
 */
private fun Modifier.keepNextRowComposed(
    index: Int,
    itemCount: Int,
    gridState: LazyGridState,
    scope: CoroutineScope,
): Modifier = this.onFocusChanged { focusState ->
    // hasFocus, not isFocused: this modifier is on the card's container, and the focusable node is
    // the clickable box inside it. isFocused only reports the node the modifier is attached to, so it
    // is always false here and the whole mechanism would silently do nothing.
    if (!focusState.hasFocus) return@onFocusChanged
    scope.launch {
        val visible = gridState.layoutInfo.visibleItemsInfo
        val firstVisible = visible.firstOrNull()?.index ?: return@launch
        val lastVisible = visible.lastOrNull()?.index ?: return@launch
        val rowStart = (index / GRID_COLUMNS) * GRID_COLUMNS
        val target = when {
            // On (or into) the last visible row: pull the following row into composition.
            index + GRID_COLUMNS > lastVisible -> rowStart + GRID_COLUMNS
            // On the first visible row: same, upwards.
            index - GRID_COLUMNS < firstVisible -> rowStart - GRID_COLUMNS
            else -> return@launch
        }
        runCatching { gridState.animateScrollToItem(target.coerceIn(0, (itemCount - 1).coerceAtLeast(0))) }
    }
}

private fun LibrarySort.label(): String = when (this) {
    LibrarySort.RECENTLY_ADDED -> "Recently added"
    LibrarySort.ALPHABETICAL -> "A–Z"
    LibrarySort.YEAR -> "Year"
    LibrarySort.RATING -> "Rating"
}

private fun WatchedFilter.label(): String = when (this) {
    WatchedFilter.ALL -> "All"
    WatchedFilter.WATCHED -> "Watched"
    WatchedFilter.UNWATCHED -> "Unwatched"
}

private fun WatchedFilter.next(): WatchedFilter = when (this) {
    WatchedFilter.ALL -> WatchedFilter.UNWATCHED
    WatchedFilter.UNWATCHED -> WatchedFilter.WATCHED
    WatchedFilter.WATCHED -> WatchedFilter.ALL
}
