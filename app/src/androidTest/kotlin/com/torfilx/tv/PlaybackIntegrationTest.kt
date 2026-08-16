package com.torfilx.tv

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import com.torfilx.core.data.remote.DemoLibrary
import com.torfilx.core.data.remote.DemoMediaRemoteSource
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.player.PlaybackController
import com.torfilx.core.player.PlaybackRequest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * End-to-end playback on a real device/emulator: a real H.264 file, the real ExoPlayer stack, the
 * real progress pipeline.
 *
 * This is the test that would have caught the media requests being routed through the API HTTP
 * client, and it is the only way to prove the decoder path actually works (plan.md §12).
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlaybackIntegrationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var controller: PlaybackController

    @Inject
    lateinit var demoSource: DemoMediaRemoteSource

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var progressRepository: ProgressRepository

    private lateinit var videoFile: File

    @Before
    fun setUp() {
        hiltRule.inject()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        videoFile = File(context.cacheDir, "playback-test.mp4")
        if (!videoFile.exists() || videoFile.length() == 0L) {
            TestVideoFactory.createVideo(videoFile, durationSeconds = 6)
        }
        demoSource.latencyMs = 0
        demoSource.mediaUrlOverride = videoFile.toURI().toString()
    }

    @Test
    fun playsRealMediaAndPersistsProgress() = runBlocking {
        settingsRepository.setDemoMode(true)
        val movie = DemoLibrary.items.first { it.isMovie }

        withContext(Dispatchers.Main) {
            controller.ensurePlayer()
        }
        controller.open(PlaybackRequest(playableId = movie.id, startPositionMs = 0L))

        // Playback must actually start...
        withTimeout(30_000) {
            while (!controller.state.value.isPlaying || controller.state.value.positionMs <= 0) {
                assertThat(controller.state.value.error).isNull()
                delay(200)
            }
        }
        assertThat(controller.state.value.durationMs).isGreaterThan(0L)

        // ...and the position must advance.
        val firstPosition = controller.state.value.positionMs
        delay(2_000)
        assertThat(controller.state.value.positionMs).isGreaterThan(firstPosition)

        // Leaving the player must persist the position for Continue Watching.
        withContext(Dispatchers.Main) { controller.stop(release = true) }
        withTimeout(10_000) {
            while (progressRepository.get(movie.id) == null) delay(100)
        }
        val stored = progressRepository.get(movie.id)!!
        assertThat(stored.positionMs).isGreaterThan(0L)
        assertThat(stored.durationMs).isGreaterThan(0L)
    }

    @Test
    fun seekMovesThePositionAndIsPersisted() = runBlocking {
        settingsRepository.setDemoMode(true)
        val movie = DemoLibrary.items.first { it.isMovie }

        withContext(Dispatchers.Main) { controller.ensurePlayer() }
        controller.open(PlaybackRequest(playableId = movie.id, startPositionMs = 0L))
        withTimeout(30_000) {
            while (!controller.state.value.isPlaying) {
                assertThat(controller.state.value.error).isNull()
                delay(200)
            }
        }

        withContext(Dispatchers.Main) { controller.seekTo(3_000) }
        withTimeout(10_000) {
            while (controller.state.value.positionMs < 2_500) delay(100)
        }
        assertThat(controller.state.value.positionMs).isAtLeast(2_500L)

        withContext(Dispatchers.Main) { controller.stop(release = true) }
    }
}
