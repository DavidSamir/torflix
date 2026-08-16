package com.torfilx.feature.player

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.player.AspectMode
import com.torfilx.core.player.PlaybackError
import com.torfilx.core.player.PlayerUiState
import com.torfilx.core.ui.component.ErrorState
import com.torfilx.core.ui.component.TvButton
import com.torfilx.core.ui.component.TvChip
import com.torfilx.core.ui.focus.RemoteKeys
import com.torfilx.core.ui.focus.onRemoteKey
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.util.Format
import kotlinx.coroutines.delay

private const val CONTROLS_TIMEOUT_MS = 4_000L
private const val SEEK_COMMIT_DELAY_MS = 300L
private const val BUFFERING_SPINNER_DELAY_MS = 500L

/**
 * The full-screen player.
 *
 * Key handling follows the plan (§5.1, §7.4): ←/→ always seek (never move focus), OK toggles the
 * overlay or activates the focused control, and the first Back press hides the overlay while the
 * second leaves — so nobody exits a film by accident.
 */
@Composable
fun PlayerScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controlsVisible by viewModel.controlsVisible.collectAsStateWithLifecycle()
    val pendingSeek by viewModel.pendingSeekMs.collectAsStateWithLifecycle()
    val displaySwitching by viewModel.displaySwitching.collectAsStateWithLifecycle()
    val autoSkipIntro by viewModel.skipIntroAutomatically.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val rootFocus = remember { FocusRequester() }

    KeepScreenOn(active = state.isPlaying)
    PauseWhenBackgrounded(onBackground = viewModel::onBackground)

    // Commit an accumulated seek once the user stops pressing the key.
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            delay(SEEK_COMMIT_DELAY_MS)
            viewModel.commitSeek()
        }
    }

    // Auto-hide the overlay, but never while a modal decision is on screen.
    LaunchedEffect(controlsVisible, state.isPlaying, state.showStillWatching) {
        if (controlsVisible && state.isPlaying && !state.showStillWatching) {
            delay(CONTROLS_TIMEOUT_MS)
            viewModel.hideControls()
        }
    }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // Auto-skip fires once per intro window, not on every frame of it.
    LaunchedEffect(state.showSkipIntro, autoSkipIntro) {
        if (autoSkipIntro && state.showSkipIntro) viewModel.skipIntro()
    }

    BackHandler(enabled = true) {
        if (controlsVisible) {
            viewModel.hideControls()
        } else {
            viewModel.leavePlayer()
            onExit()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TorfilxColors.Background)
            .focusRequester(rootFocus)
            .focusable()
            .onRemoteKey { key, isKeyDown, _ ->
                if (!isKeyDown) return@onRemoteKey false
                viewModel.noteUserInput()
                when (key) {
                    RemoteKeys.LEFT -> {
                        viewModel.nudgeSeek(-SEEK_STEP_MS); true
                    }

                    RemoteKeys.RIGHT -> {
                        viewModel.nudgeSeek(SEEK_STEP_MS); true
                    }

                    RemoteKeys.REWIND -> {
                        viewModel.nudgeSeek(-LONG_SEEK_STEP_MS); true
                    }

                    RemoteKeys.FAST_FORWARD -> {
                        viewModel.nudgeSeek(LONG_SEEK_STEP_MS); true
                    }

                    RemoteKeys.PLAY_PAUSE, RemoteKeys.PLAY, RemoteKeys.PAUSE -> {
                        viewModel.togglePlayPause(); true
                    }

                    RemoteKeys.NEXT -> {
                        viewModel.playNextEpisode(); true
                    }

                    RemoteKeys.PREVIOUS -> {
                        viewModel.playPreviousEpisode(); true
                    }

                    in RemoteKeys.CONFIRM_KEYS -> {
                        if (!controlsVisible) {
                            viewModel.togglePlayPause()
                            true
                        } else {
                            false // let the focused control handle it
                        }
                    }

                    RemoteKeys.UP, RemoteKeys.DOWN, RemoteKeys.MENU -> {
                        viewModel.showControls()
                        key == RemoteKeys.MENU
                    }

                    else -> false
                }
            },
    ) {
        VideoSurface(viewModel = viewModel, aspectMode = state.aspectMode)

        // While the TV renegotiates HDMI after a refresh-rate switch it shows garbage; cover it.
        if (displaySwitching) {
            Box(Modifier.fillMaxSize().background(TorfilxColors.Background))
        }

        if (state.isBuffering) {
            BufferingIndicator()
        }

        state.error?.let { error ->
            PlayerErrorOverlay(
                error = error,
                onRetry = viewModel::retry,
                onEnableSharing = viewModel::enableSharingAndRetry,
                onExit = {
                    viewModel.leavePlayer()
                    onExit()
                },
            )
            return@Box
        }

        if (state.showStillWatching) {
            StillWatchingOverlay(
                onContinue = { viewModel.dismissStillWatching(continueWatching = true) },
                onStop = {
                    viewModel.dismissStillWatching(continueWatching = false)
                    viewModel.leavePlayer()
                    onExit()
                },
            )
            return@Box
        }

        if (state.showSkipIntro) {
            TvButton(
                text = "Skip intro",
                onClick = viewModel::skipIntro,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 64.dp, bottom = 120.dp),
                autoFocus = !controlsVisible,
            )
        }

        state.nextEpisodeCountdownSeconds?.let { seconds ->
            NextEpisodeCard(
                seconds = seconds,
                title = state.nextEpisode?.let { episode ->
                    "${Format.episodeCode(episode)}${episode.title?.let { " · $it" }.orEmpty()}"
                }.orEmpty(),
                onPlayNow = viewModel::playNextEpisode,
                onCancel = viewModel::cancelNextEpisodeCountdown,
            )
        }

        if (controlsVisible) {
            PlayerControls(
                state = state,
                pendingSeekMs = pendingSeek,
                onPlayPause = viewModel::togglePlayPause,
                onSeekTo = viewModel::seekTo,
                onRestart = { viewModel.seekTo(0) },
                onNextEpisode = viewModel::playNextEpisode,
                onSelectAudio = viewModel::selectAudioTrack,
                onSelectSubtitle = viewModel::selectSubtitleTrack,
                onAspect = viewModel::setAspectMode,
                onSpeed = viewModel::setSpeed,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    DisposableEffect(activity) {
        onDispose { activity?.let { /* display mode reset handled by FrameRateController */ } }
    }
}

@Composable
private fun VideoSurface(viewModel: PlayerViewModel, aspectMode: AspectMode) {
    val player = viewModel.player
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PlayerView(context).apply {
                useController = false // the overlay above is the controller
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
            }
        },
        update = { view ->
            view.player = player
            view.resizeMode = when (aspectMode) {
                AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        onReset = { view -> view.player = null },
    )
}

/**
 * The spinner only appears after a short delay: on a LAN most rebuffers are shorter than that, and a
 * spinner that flashes on every seek looks broken (plan.md §7.4).
 */
@Composable
private fun BufferingIndicator() {
    val visibleState = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(BUFFERING_SPINNER_DELAY_MS)
        visibleState.value = true
    }
    if (!visibleState.value) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Buffering…",
            style = MaterialTheme.typography.titleLarge,
            color = TorfilxColors.TextPrimary,
        )
    }
}

@Composable
private fun PlayerErrorOverlay(
    error: PlaybackError,
    onRetry: () -> Unit,
    onEnableSharing: () -> Unit,
    onExit: () -> Unit,
) {
    val sharingOff = error is PlaybackError.SharingNotEnabled
    ErrorState(
        title = when (error) {
            is PlaybackError.Network -> "Lost the server"
            is PlaybackError.Unsupported -> "Can't play this file"
            is PlaybackError.NotFound -> "Not available"
            is PlaybackError.Unauthorized -> "Access denied"
            is PlaybackError.SharingNotEnabled -> "Sharing is off"
            is PlaybackError.OutOfSpace -> "Not enough space"
            is PlaybackError.Unknown -> "Playback problem"
        },
        message = error.message,
        // Retrying is pointless for a format this device cannot decode, or for a consent decision
        // that has to be made in Settings.
        primaryActionLabel = when {
            sharingOff -> "Enable sharing"
            error.isRetryable() -> "Retry"
            else -> null
        },
        onPrimaryAction = when {
            sharingOff -> onEnableSharing
            error.isRetryable() -> onRetry
            else -> null
        },
        secondaryActionLabel = "Back",
        onSecondaryAction = onExit,
    )
}

@Composable
private fun StillWatchingOverlay(onContinue: () -> Unit, onStop: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(TorfilxColors.ScrimStrong),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Are you still watching?",
                style = MaterialTheme.typography.headlineLarge,
                color = TorfilxColors.TextPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(text = "Continue watching", onClick = onContinue, autoFocus = true)
                TvButton(text = "Stop", onClick = onStop, primary = false)
            }
        }
    }
}

@Composable
private fun NextEpisodeCard(
    seconds: Int,
    title: String,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(end = 64.dp, bottom = 64.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier
                .background(TorfilxColors.ScrimStrong)
                .padding(20.dp)
                .width(360.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Next episode in $seconds…",
                style = MaterialTheme.typography.titleMedium,
                color = TorfilxColors.TextPrimary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(text = "Play now", onClick = onPlayNow, autoFocus = true)
                TvButton(text = "Cancel", onClick = onCancel, primary = false)
            }
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlayerUiState,
    pendingSeekMs: Long?,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onRestart: () -> Unit,
    onNextEpisode: () -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onAspect: (AspectMode) -> Unit,
    onSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayPosition = pendingSeekMs ?: state.positionMs

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(TorfilxColors.Transparent, TorfilxColors.ScrimStrong),
                ),
            )
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleLarge,
            color = TorfilxColors.TextPrimary,
        )
        state.subtitle?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.TextSecondary,
            )
        }

        Timeline(
            positionMs = displayPosition,
            bufferedMs = state.bufferedPositionMs,
            durationMs = state.durationMs,
            isScrubbing = pendingSeekMs != null,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = Format.timecode(displayPosition),
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.TextSecondary,
            )
            Text(
                text = "-${Format.timecode((state.durationMs - displayPosition).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.TextSecondary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = if (state.isPlaying) "❚❚ Pause" else "▶ Play",
                onClick = onPlayPause,
                autoFocus = true,
            )
            TvButton(text = "↺ Restart", onClick = onRestart, primary = false)
            if (state.nextEpisode != null) {
                TvButton(text = "Next episode", onClick = onNextEpisode, primary = false)
            }
            TvButton(
                text = "Aspect: ${state.aspectMode.label}",
                onClick = { onAspect(state.aspectMode.next()) },
                primary = false,
            )
            TvButton(
                text = "Speed ${state.playbackSpeed}×",
                onClick = { onSpeed(state.playbackSpeed.nextSpeed()) },
                primary = false,
            )
        }

        if (state.audioTracks.isNotEmpty() || state.subtitleTracks.isNotEmpty()) {
            TrackPickers(
                state = state,
                onSelectAudio = onSelectAudio,
                onSelectSubtitle = onSelectSubtitle,
            )
        }
    }
}

@Composable
private fun TrackPickers(
    state: PlayerUiState,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.audioTracks.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Audio",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                state.audioTracks.forEach { track ->
                    TvChip(
                        text = track.label,
                        selected = track.isSelected,
                        onClick = { if (track.isSelectable) onSelectAudio(track.id) },
                    )
                }
            }
        }
        if (state.subtitleTracks.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Subtitles",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TvChip(
                    text = "Off",
                    selected = !state.subtitlesEnabled,
                    onClick = { onSelectSubtitle(null) },
                )
                state.subtitleTracks.forEach { track ->
                    TvChip(
                        // Unselectable tracks say why, rather than silently doing nothing.
                        text = if (track.isSelectable) track.label else "${track.label} (unavailable)",
                        selected = track.isSelected,
                        onClick = { if (track.isSelectable) onSelectSubtitle(track.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Timeline(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    isScrubbing: Boolean,
) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(TorfilxColors.ProgressTrack),
    ) {
        Box(
            Modifier
                .fillMaxWidth(bufferedFraction)
                .fillMaxHeight()
                .background(TorfilxColors.SurfaceHighest),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(if (isScrubbing) TorfilxColors.Focus else TorfilxColors.ProgressFill),
        )
    }
}

@Composable
private fun KeepScreenOn(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        val window = context.findActivity()?.window
        if (active) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** A TV video app pauses when it loses the screen — it never plays invisibly (plan.md §7.6). */
@Composable
private fun PauseWhenBackgrounded(onBackground: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var context: android.content.Context? = this
    while (context is android.content.ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private fun AspectMode.next(): AspectMode = when (this) {
    AspectMode.FIT -> AspectMode.FILL
    AspectMode.FILL -> AspectMode.ZOOM
    AspectMode.ZOOM -> AspectMode.FIT
}

private fun Float.nextSpeed(): Float = when (this) {
    1f -> 1.25f
    1.25f -> 1.5f
    1.5f -> 2f
    2f -> 0.5f
    0.5f -> 0.75f
    else -> 1f
}

private const val SEEK_STEP_MS = 10_000L
private const val LONG_SEEK_STEP_MS = 30_000L

private fun PlaybackError.isRetryable(): Boolean = when (this) {
    is PlaybackError.Unsupported, is PlaybackError.SharingNotEnabled -> false
    is PlaybackError.Network, is PlaybackError.NotFound, is PlaybackError.Unauthorized,
    is PlaybackError.OutOfSpace, is PlaybackError.Unknown,
    -> true
}
