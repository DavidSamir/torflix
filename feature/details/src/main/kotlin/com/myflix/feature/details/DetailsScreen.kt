package com.myflix.feature.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.myflix.core.model.Episode
import com.myflix.core.model.MediaItem
import com.myflix.core.model.PlayAction
import com.myflix.core.model.PlaybackProgress
import com.myflix.core.model.Season
import com.myflix.core.ui.component.CardProgressBar
import com.myflix.core.ui.component.ErrorState
import com.myflix.core.ui.component.SkeletonRow
import com.myflix.core.ui.component.TvButton
import com.myflix.core.ui.component.TvChip
import com.myflix.core.ui.focus.onMenuKey
import com.myflix.core.ui.image.Artwork
import com.myflix.core.ui.theme.LocalMyflixDimens
import com.myflix.core.ui.theme.MyflixColors
import com.myflix.core.ui.util.Format

/**
 * Details for a movie or a show.
 *
 * The primary button is derived from local progress, so returning from the player relabels it
 * immediately ("Resume S1:E3" → "Play S1:E4") without a server round trip (plan.md §6.3–6.4).
 */
@Composable
fun DetailsScreen(
    onPlay: (PlayAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize().background(MyflixColors.Background)) {
        when (val current = state) {
            DetailsUiState.Loading -> DetailsSkeleton()

            is DetailsUiState.Error -> ErrorState(
                title = if (current.itemMissing) "Not available" else "Can't load this title",
                message = current.message,
                primaryActionLabel = if (current.itemMissing) "Back" else "Retry",
                onPrimaryAction = if (current.itemMissing) onBack else viewModel::refresh,
                secondaryActionLabel = if (current.itemMissing) null else "Back",
                onSecondaryAction = if (current.itemMissing) null else onBack,
            )

            is DetailsUiState.MovieContent -> MovieDetails(
                state = current,
                onPlay = { onPlay(current.primaryAction) },
                onToggleMyList = viewModel::toggleMyList,
                onMarkWatched = { viewModel.markWatched(it, current.item.runtimeMs) },
            )

            is DetailsUiState.ShowContent -> ShowDetailsContent(
                state = current,
                onPlayPrimary = { onPlay(current.primaryAction) },
                onPlayEpisode = { episode, progress ->
                    onPlay(
                        PlayAction.PlayEpisode(
                            episode = episode,
                            positionMs = com.myflix.core.model.ResumeRules.resumePositionMs(progress),
                            kind = PlayAction.EpisodeActionKind.RESUME,
                        ),
                    )
                },
                onSelectSeason = viewModel::selectSeason,
                onToggleMyList = viewModel::toggleMyList,
                onToggleEpisodeWatched = viewModel::markEpisodeWatched,
            )
        }
    }
}

@Composable
private fun MovieDetails(
    state: DetailsUiState.MovieContent,
    onPlay: () -> Unit,
    onToggleMyList: () -> Unit,
    onMarkWatched: (Boolean) -> Unit,
) {
    val dimens = LocalMyflixDimens.current
    Box(Modifier.fillMaxSize()) {
        Backdrop(item = state.item)
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = dimens.overscanHorizontal)
                .width(620.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.item.title,
                style = MaterialTheme.typography.displayMedium,
                color = MyflixColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaRow(item = state.item)
            state.item.overview?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MyflixColors.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.progress?.takeIf { it.fraction > 0f && !it.watched }?.let { progress ->
                Text(
                    text = "${Format.runtime(progress.remainingMs)} left",
                    style = MaterialTheme.typography.labelLarge,
                    color = MyflixColors.TextSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    text = state.primaryAction.label(),
                    onClick = onPlay,
                    autoFocus = true,
                )
                TvButton(
                    text = if (state.inMyList) "✓ My List" else "+ My List",
                    onClick = onToggleMyList,
                    primary = false,
                )
                TvButton(
                    text = if (state.progress?.watched == true) "Mark unwatched" else "Mark watched",
                    onClick = { onMarkWatched(state.progress?.watched != true) },
                    primary = false,
                )
            }
        }
    }
}

@Composable
private fun ShowDetailsContent(
    state: DetailsUiState.ShowContent,
    onPlayPrimary: () -> Unit,
    onPlayEpisode: (Episode, PlaybackProgress?) -> Unit,
    onSelectSeason: (Season) -> Unit,
    onToggleMyList: () -> Unit,
    onToggleEpisodeWatched: (Episode, Boolean) -> Unit,
) {
    val dimens = LocalMyflixDimens.current
    val show = state.details.show

    LazyColumn(
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(bottom = dimens.overscanVertical * 2),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "header") {
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                Backdrop(item = show)
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = dimens.overscanHorizontal)
                        .width(620.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = show.title,
                        style = MaterialTheme.typography.displayMedium,
                        color = MyflixColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MetaRow(item = show)
                    show.overview?.let { overview ->
                        Text(
                            text = overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MyflixColors.TextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(
                            text = state.primaryAction.label(),
                            onClick = onPlayPrimary,
                            enabled = state.primaryAction != PlayAction.Unavailable,
                            autoFocus = true,
                        )
                        TvButton(
                            text = if (state.inMyList) "✓ My List" else "+ My List",
                            onClick = onToggleMyList,
                            primary = false,
                        )
                    }
                }
            }
        }

        if (state.details.seasons.size > 1) {
            item(key = "seasons") {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusGroup()
                        .focusRestorer(),
                    contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.details.seasons, key = { it.id }) { season ->
                        TvChip(
                            text = season.displayName(),
                            selected = season.id == state.selectedSeasonId,
                            onClick = { onSelectSeason(season) },
                        )
                    }
                }
            }
        }

        val episodes = state.episodes
        if (episodes.isEmpty()) {
            item(key = "episodes-empty") {
                if (state.episodesLoading) {
                    SkeletonRow(landscape = true, count = 3)
                } else {
                    Text(
                        text = "No episodes found for this season.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MyflixColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
                    )
                }
            }
        } else {
            items(episodes, key = { it.id }, contentType = { "episode" }) { episode ->
                EpisodeRow(
                    episode = episode,
                    show = show,
                    progress = state.progressByEpisode[episode.id],
                    onClick = { onPlayEpisode(episode, state.progressByEpisode[episode.id]) },
                    onToggleWatched = {
                        onToggleEpisodeWatched(
                            episode,
                            state.progressByEpisode[episode.id]?.watched != true,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    show: MediaItem,
    progress: PlaybackProgress?,
    onClick: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    val dimens = LocalMyflixDimens.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.overscanHorizontal)
            .clip(RoundedCornerShape(dimens.cornerRadius))
            .background(if (isFocused) MyflixColors.SurfaceHigh else MyflixColors.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .onMenuKey(onToggleWatched)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .width(dimens.landscapeWidth)
                .height(dimens.landscapeHeight)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            // Missing episode thumbnails fall back to the show backdrop rather than a grey box.
            Artwork(
                url = episode.thumbUrl ?: show.images.backdrop,
                title = Format.episodeCode(episode),
                seed = episode.id,
                widthDp = dimens.landscapeWidth,
                heightDp = dimens.landscapeHeight,
                modifier = Modifier.fillMaxSize(),
            )
            progress?.takeIf { it.fraction > 0f && !it.watched }?.let {
                CardProgressBar(
                    fraction = it.fraction,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                // A show with no episode titles still reads properly: "S1:E4 · Episode 4".
                text = "${Format.episodeCode(episode)} · ${episode.title ?: "Episode ${episode.episodeNumber}"}",
                style = MaterialTheme.typography.titleMedium,
                color = MyflixColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    Format.runtime(episode.runtimeMs).takeIf { it.isNotEmpty() },
                    if (progress?.watched == true) "Watched" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MyflixColors.TextSecondary,
            )
            episode.overview?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MyflixColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Backdrop(item: MediaItem) {
    Box(Modifier.fillMaxSize()) {
        Artwork(
            url = item.images.backdrop ?: item.images.poster,
            title = item.title,
            seed = item.id,
            showGeneratedLabel = false,
            widthDp = 960.dp,
            heightDp = 540.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to MyflixColors.ScrimStrong,
                    0.6f to MyflixColors.ScrimSoft,
                    1f to MyflixColors.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.55f to MyflixColors.Transparent,
                    1f to MyflixColors.Background,
                ),
            ),
        )
    }
}

@Composable
private fun MetaRow(item: MediaItem) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Format.rating(item.communityRating)?.let { rating ->
            Text(
                text = "★ $rating",
                style = MaterialTheme.typography.labelLarge,
                color = MyflixColors.Warning,
            )
        }
        Text(
            text = Format.metaLine(item),
            style = MaterialTheme.typography.labelLarge,
            color = MyflixColors.TextSecondary,
        )
    }
}

@Composable
private fun DetailsSkeleton() {
    Column(
        Modifier.fillMaxSize().padding(top = 60.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SkeletonRow(landscape = true, count = 3)
        SkeletonRow(count = 5)
    }
}

private fun Season.displayName(): String = when {
    isSpecials -> name ?: "Specials"
    else -> name ?: "Season $number"
}

private fun PlayAction.label(): String = when (this) {
    PlayAction.Unavailable -> "Unavailable"
    is PlayAction.PlayMovie -> if (restart) "▶ Play again" else "▶ Play"
    is PlayAction.ResumeMovie -> "▶ Resume ${Format.timecode(positionMs)}"
    is PlayAction.PlayEpisode -> when (kind) {
        PlayAction.EpisodeActionKind.RESUME -> "▶ Resume ${Format.episodeCode(episode)}"
        PlayAction.EpisodeActionKind.NEXT_UP -> "▶ Play ${Format.episodeCode(episode)}"
        PlayAction.EpisodeActionKind.START_OVER -> "▶ Play ${Format.episodeCode(episode)} again"
    }
}
