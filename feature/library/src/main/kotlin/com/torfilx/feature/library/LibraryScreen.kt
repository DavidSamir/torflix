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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
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
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(mode) { viewModel.setMode(mode) }

    val dimens = LocalTorfilxDimens.current
    val gridState = rememberLazyGridState()

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
                    LibraryMode.SHOWS -> "No shows match these filters"
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
                    items = state.cards,
                    key = { card -> card.item.id },
                    contentType = { "poster" },
                ) { card: MediaCard ->
                    PosterCard(
                        card = card,
                        onClick = { onOpenDetails(card.item.id) },
                        onLongClick = { viewModel.toggleMyList(card) },
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
