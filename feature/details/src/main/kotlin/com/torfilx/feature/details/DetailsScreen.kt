package com.torfilx.feature.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaSource
import com.torfilx.core.model.PlayAction
import com.torfilx.core.ui.component.ErrorState
import com.torfilx.core.ui.component.SharingConsentDialog
import com.torfilx.core.ui.component.SkeletonRow
import com.torfilx.core.ui.component.TvButton
import com.torfilx.core.ui.image.Artwork
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.util.Format

/**
 * Details for one film.
 *
 * Every quality the catalogue offers is a separate button, so the viewer chooses what to stream
 * rather than the app guessing. Back is both the remote's Back key and a visible button, because on
 * a full-screen backdrop there is otherwise nothing that says "you can leave".
 */
@Composable
fun DetailsScreen(
    onPlaySource: (itemId: String, sourceId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingTorrent by viewModel.pendingTorrentSource.collectAsStateWithLifecycle()

    BackHandler(enabled = pendingTorrent != null) { viewModel.onConsentDeclined() }

    val play: (MediaSource) -> Unit = { source ->
        // Consent is collected before the first byte moves, not after.
        viewModel.requestTorrentPlayback(source)?.let { ready ->
            onPlaySource(viewModel.itemIdForPlayback, ready.id)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(TorfilxColors.Background)) {
        when (val current = state) {
            DetailsUiState.Loading -> DetailsSkeleton()

            is DetailsUiState.Error -> ErrorState(
                title = "Can't open this title",
                message = current.message,
                primaryActionLabel = "Back",
                onPrimaryAction = onBack,
            )

            is DetailsUiState.Content -> FilmDetails(
                state = current,
                sources = viewModel.torrentSources,
                torrentAvailable = viewModel.torrentAvailable,
                onPlay = play,
                onToggleMyList = viewModel::toggleMyList,
                onMarkWatched = { viewModel.markWatched(it, current.item.runtimeMs) },
                onBack = onBack,
            )
        }

        if (pendingTorrent != null) {
            SharingConsentDialog(
                storageSummary = "While sharing, the app keeps part of what you watch on this device " +
                    "and uploads it to others. It never uses more than the share of free space set in " +
                    "Settings, and clears the oldest titles first.",
                onAccept = {
                    viewModel.onConsentAccepted { source ->
                        onPlaySource(viewModel.itemIdForPlayback, source.id)
                    }
                },
                onDecline = viewModel::onConsentDeclined,
            )
        }
    }
}

@Composable
private fun FilmDetails(
    state: DetailsUiState.Content,
    sources: List<MediaSource>,
    torrentAvailable: Boolean,
    onPlay: (MediaSource) -> Unit,
    onToggleMyList: () -> Unit,
    onMarkWatched: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val dimens = LocalTorfilxDimens.current
    Box(Modifier.fillMaxSize()) {
        Backdrop(item = state.item)

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = dimens.overscanHorizontal)
                .width(660.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.item.title,
                style = MaterialTheme.typography.displayMedium,
                color = TorfilxColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaRow(item = state.item)
            state.item.overview?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TorfilxColors.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.progress?.takeIf { it.fraction > 0f && !it.watched }?.let { progress ->
                Text(
                    text = "${Format.runtime(progress.remainingMs)} left",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.TextSecondary,
                )
            }

            when {
                !torrentAvailable -> Text(
                    text = "BitTorrent is not available on this device, so this title cannot be played.",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.Error,
                )

                sources.isEmpty() -> Text(
                    text = "This catalogue entry has no valid magnet link.",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.Error,
                )

                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    sources.forEachIndexed { index, source ->
                        TvButton(
                            text = state.primaryAction.label(source, sources.size),
                            onClick = { onPlay(source) },
                            autoFocus = index == 0,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                TvButton(text = "← Back", onClick = onBack, primary = false)
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
                    0f to TorfilxColors.ScrimStrong,
                    0.6f to TorfilxColors.ScrimSoft,
                    1f to TorfilxColors.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.55f to TorfilxColors.Transparent,
                    1f to TorfilxColors.Background,
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
                color = TorfilxColors.Warning,
            )
        }
        Text(
            text = Format.metaLine(item),
            style = MaterialTheme.typography.labelLarge,
            color = TorfilxColors.TextSecondary,
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

/**
 * Button label: the action for the film, plus the quality when there is more than one to choose
 * from ("▶ Resume · 1080p").
 */
private fun PlayAction.label(source: MediaSource, sourceCount: Int): String {
    val action = when (this) {
        PlayAction.Unavailable -> "Unavailable"
        is PlayAction.Play -> if (restart) "▶ Play again" else "▶ Play"
        is PlayAction.Resume -> "▶ Resume ${Format.timecode(positionMs)}"
    }
    if (sourceCount <= 1) return action
    val quality = source.label?.substringAfter("· ", "") ?: ""
    return if (quality.isBlank()) action else "$action · $quality"
}
