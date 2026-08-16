package com.myflix.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NextEpisodeSelectorTest {

    private val show = MediaItem(id = "s1", type = MediaType.SHOW, title = "The Show")

    private fun episode(season: Int, number: Int) = Episode(
        id = "s1e${season}_$number",
        showId = "s1",
        seasonId = "season$season",
        seasonNumber = season,
        episodeNumber = number,
        title = "S${season}E$number",
        runtimeMs = 45 * 60_000L,
    )

    private fun details(vararg seasons: Int): ShowDetails {
        val seasonList = seasons.map {
            Season(id = "season$it", showId = "s1", number = it, episodeCount = 3)
        }
        val episodes = seasonList.associate { season ->
            season.id to (1..3).map { episode(season.number, it) }
        }
        return ShowDetails(show, seasonList, episodes)
    }

    private fun watched(id: String) = id to PlaybackProgress(id, 44 * 60_000L, 45 * 60_000L, watched = true)

    @Test
    fun `specials are ordered last regardless of declaration order`() {
        val d = details(0, 2, 1)
        val order = d.episodesInAiredOrder.map { it.id }
        assertThat(order.first()).isEqualTo("s1e1_1")
        assertThat(order.last()).isEqualTo("s1e0_3")
    }

    @Test
    fun `fresh show starts at the first episode`() {
        val action = NextEpisodeSelector.primaryAction(details(1, 2), emptyMap())
        assertThat(action).isInstanceOf(PlayAction.PlayEpisode::class.java)
        val play = action as PlayAction.PlayEpisode
        assertThat(play.episode.id).isEqualTo("s1e1_1")
        assertThat(play.positionMs).isEqualTo(0L)
        assertThat(play.kind).isEqualTo(PlayAction.EpisodeActionKind.NEXT_UP)
    }

    @Test
    fun `an in-progress episode wins over the first unwatched one`() {
        val d = details(1, 2)
        val progress = mapOf(
            watched("s1e1_1"),
            "s1e1_2" to PlaybackProgress("s1e1_2", 20 * 60_000L, 45 * 60_000L),
        )
        val play = NextEpisodeSelector.primaryAction(d, progress) as PlayAction.PlayEpisode
        assertThat(play.episode.id).isEqualTo("s1e1_2")
        assertThat(play.positionMs).isEqualTo(20 * 60_000L)
        assertThat(play.kind).isEqualTo(PlayAction.EpisodeActionKind.RESUME)
    }

    @Test
    fun `next up crosses the season boundary`() {
        val d = details(1, 2)
        val progress = (1..3).associate { watched("s1e1_$it") }
        val play = NextEpisodeSelector.primaryAction(d, progress) as PlayAction.PlayEpisode
        assertThat(play.episode.id).isEqualTo("s1e2_1")
        assertThat(play.kind).isEqualTo(PlayAction.EpisodeActionKind.NEXT_UP)
    }

    @Test
    fun `fully watched show offers a restart from the very first episode`() {
        val d = details(1, 2)
        val progress = buildMap {
            (1..3).forEach { putAll(mapOf(watched("s1e1_$it"), watched("s1e2_$it"))) }
        }
        val play = NextEpisodeSelector.primaryAction(d, progress) as PlayAction.PlayEpisode
        assertThat(play.episode.id).isEqualTo("s1e1_1")
        assertThat(play.kind).isEqualTo(PlayAction.EpisodeActionKind.START_OVER)
    }

    @Test
    fun `show with no episodes is unavailable`() {
        val d = ShowDetails(show, emptyList(), emptyMap())
        assertThat(NextEpisodeSelector.primaryAction(d, emptyMap())).isEqualTo(PlayAction.Unavailable)
    }

    @Test
    fun `next and previous episode navigation respects aired order and ends`() {
        val d = details(1, 2)
        assertThat(NextEpisodeSelector.nextEpisodeAfter(d, "s1e1_3")?.id).isEqualTo("s1e2_1")
        assertThat(NextEpisodeSelector.nextEpisodeAfter(d, "s1e2_3")).isNull()
        assertThat(NextEpisodeSelector.previousEpisodeBefore(d, "s1e2_1")?.id).isEqualTo("s1e1_3")
        assertThat(NextEpisodeSelector.previousEpisodeBefore(d, "s1e1_1")).isNull()
        assertThat(NextEpisodeSelector.nextEpisodeAfter(d, "unknown-id")).isNull()
    }

    @Test
    fun `movie actions cover fresh watched and in-progress states`() {
        val movie = MediaItem(id = "m1", type = MediaType.MOVIE, title = "Movie")
        assertThat(NextEpisodeSelector.movieAction(movie, null))
            .isEqualTo(PlayAction.PlayMovie("m1", restart = false))

        val inProgress = PlaybackProgress("m1", 40 * 60_000L, 120 * 60_000L)
        assertThat(NextEpisodeSelector.movieAction(movie, inProgress))
            .isEqualTo(PlayAction.ResumeMovie("m1", 40 * 60_000L))

        val done = PlaybackProgress("m1", 119 * 60_000L, 120 * 60_000L, watched = true)
        assertThat(NextEpisodeSelector.movieAction(movie, done))
            .isEqualTo(PlayAction.PlayMovie("m1", restart = true))
    }
}
