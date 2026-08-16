package com.torfilx.tv.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.torfilx.core.model.PlayAction
import com.torfilx.feature.details.DetailsScreen
import com.torfilx.feature.details.DetailsViewModel
import com.torfilx.feature.home.HomeScreen
import com.torfilx.feature.library.LibraryMode
import com.torfilx.feature.library.LibraryScreen
import com.torfilx.feature.player.PlayerScreen
import com.torfilx.feature.player.PlayerViewModel
import com.torfilx.feature.search.SearchScreen
import com.torfilx.feature.settings.SettingsScreen

/** Navigation destinations. Route strings are centralised so no screen builds a URL by hand. */
object Routes {
    const val HOME = "home"
    const val MOVIES = "movies"
    const val SHOWS = "shows"
    const val MY_LIST = "my-list"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val DETAILS = "details/{${DetailsViewModel.ARG_ITEM_ID}}"
    const val PLAYER =
        "player/{${PlayerViewModel.ARG_PLAYABLE_ID}}?showId={${PlayerViewModel.ARG_SHOW_ID}}" +
            "&startPositionMs={${PlayerViewModel.ARG_START_POSITION}}" +
            "&sourceId={${PlayerViewModel.ARG_SOURCE_ID}}"

    fun details(itemId: String) = "details/$itemId"

    fun player(
        playableId: String,
        showId: String? = null,
        startPositionMs: Long? = null,
        sourceId: String? = null,
    ) = buildString {
        append("player/")
        append(playableId)
        append("?showId=")
        append(showId.orEmpty())
        append("&startPositionMs=")
        append(startPositionMs ?: -1L)
        append("&sourceId=")
        append(sourceId.orEmpty())
    }

    /** The tab destinations that the top bar switches between. */
    val TOP_LEVEL = setOf(HOME, MOVIES, SHOWS, MY_LIST, SEARCH, SETTINGS)
}

private const val TRANSITION_MS = 200

@Composable
fun TorfilxNavHost(
    navController: NavHostController,
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openDetails: (String) -> Unit = { itemId -> navController.navigate(Routes.details(itemId)) }

    val play: (PlayAction) -> Unit = { action ->
        when (action) {
            PlayAction.Unavailable -> Unit
            is PlayAction.PlayMovie -> navController.navigate(Routes.player(action.itemId, null, 0L))
            is PlayAction.ResumeMovie ->
                navController.navigate(Routes.player(action.itemId, null, action.positionMs))

            is PlayAction.PlayEpisode -> navController.navigate(
                Routes.player(action.episode.id, action.episode.showId, action.positionMs),
            )
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { fadeIn(tween(TRANSITION_MS)) },
        exitTransition = { fadeOut(tween(TRANSITION_MS)) },
        popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
        popExitTransition = { fadeOut(tween(TRANSITION_MS)) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenDetails = openDetails,
                onPlay = play,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.MOVIES) {
            LibraryScreen(mode = LibraryMode.MOVIES, onOpenDetails = openDetails)
        }

        composable(Routes.SHOWS) {
            LibraryScreen(mode = LibraryMode.SHOWS, onOpenDetails = openDetails)
        }

        composable(Routes.MY_LIST) {
            LibraryScreen(mode = LibraryMode.MY_LIST, onOpenDetails = openDetails)
        }

        composable(Routes.SEARCH) {
            SearchScreen(onOpenDetails = openDetails)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen()
        }

        composable(
            route = Routes.DETAILS,
            arguments = listOf(
                navArgument(DetailsViewModel.ARG_ITEM_ID) { type = NavType.StringType },
            ),
        ) {
            DetailsScreen(
                onPlay = play,
                onPlaySource = { itemId, sourceId ->
                    navController.navigate(Routes.player(itemId, sourceId = sourceId))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument(PlayerViewModel.ARG_PLAYABLE_ID) { type = NavType.StringType },
                navArgument(PlayerViewModel.ARG_SHOW_ID) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(PlayerViewModel.ARG_START_POSITION) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument(PlayerViewModel.ARG_SOURCE_ID) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
            // The player never animates in: a cross-fade over a video surface flashes.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            PlayerScreen(
                onExit = {
                    if (!navController.popBackStack()) onExitApp()
                },
            )
        }
    }
}
