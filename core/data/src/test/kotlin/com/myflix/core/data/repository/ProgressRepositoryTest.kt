package com.myflix.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.myflix.core.data.database.MyflixDatabase
import com.myflix.core.data.database.SyncState
import com.myflix.core.data.database.toEntity
import com.myflix.core.data.sync.SyncScheduler
import com.myflix.core.model.PlaybackProgress
import com.myflix.core.testing.FakeMediaRemoteSource
import com.myflix.core.testing.FakeTimeProvider
import com.myflix.core.testing.Fixtures
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the rules that decide whether the user loses their place: local-first writes, the
 * newest-wins conflict rule, and the shape of Continue Watching (plan.md §7.5, §8.2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressRepositoryTest {

    private lateinit var database: MyflixDatabase
    private lateinit var repository: ProgressRepository
    private lateinit var remote: FakeMediaRemoteSource
    private lateinit var time: FakeTimeProvider

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyflixDatabase::class.java,
        ).allowMainThreadQueries().build()

        remote = FakeMediaRemoteSource()
        time = FakeTimeProvider()
        repository = ProgressRepository(
            progressDao = database.progressDao(),
            libraryDao = database.libraryDao(),
            episodeDao = database.episodeDao(),
            remote = remote,
            timeProvider = time,
            syncScheduler = mockk<SyncScheduler>(relaxed = true),
            scope = CoroutineScope(StandardTestDispatcher()),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `saving progress writes locally straight away and queues it for upload`() = runTest {
        repository.save("movie-1", positionMs = 30 * 60_000, durationMs = 120 * 60_000)

        val stored = database.progressDao().get("movie-1")
        assertThat(stored).isNotNull()
        assertThat(stored!!.positionMs).isEqualTo(30 * 60_000)
        assertThat(stored.syncState).isEqualTo(SyncState.PENDING.name)
        assertThat(stored.updatedAtMs).isEqualTo(time.serverAdjustedNowMs())
    }

    @Test
    fun `crossing ninety percent marks the item watched automatically`() = runTest {
        repository.save("movie-1", positionMs = 109 * 60_000, durationMs = 120 * 60_000)
        assertThat(database.progressDao().get("movie-1")!!.watched).isTrue()
    }

    @Test
    fun `server progress only wins when it is newer than the local write`() = runTest {
        repository.save("movie-1", positionMs = 40 * 60_000, durationMs = 120 * 60_000)

        // An older value from another client must not roll the position back.
        remote.serverProgress += PlaybackProgress(
            itemId = "movie-1",
            positionMs = 5 * 60_000,
            durationMs = 120 * 60_000,
            updatedAtMs = time.nowMs() - 1_000,
        )
        repository.reconcileFromServer()
        assertThat(database.progressDao().get("movie-1")!!.positionMs).isEqualTo(40 * 60_000)

        // A newer one does win.
        remote.serverProgress.clear()
        remote.serverProgress += PlaybackProgress(
            itemId = "movie-1",
            positionMs = 90 * 60_000,
            durationMs = 120 * 60_000,
            updatedAtMs = time.nowMs() + 10_000,
        )
        repository.reconcileFromServer()
        assertThat(database.progressDao().get("movie-1")!!.positionMs).isEqualTo(90 * 60_000)
    }

    @Test
    fun `pushing pending marks rows synced and reports failures for retry`() = runTest {
        repository.save("movie-1", positionMs = 10_000, durationMs = 100_000)

        assertThat(repository.pushPending()).isTrue()
        assertThat(remote.putProgressCalls).hasSize(1)
        assertThat(database.progressDao().get("movie-1")!!.syncState).isEqualTo(SyncState.SYNCED.name)

        repository.save("movie-1", positionMs = 20_000, durationMs = 100_000)
        remote.failure = { com.myflix.core.common.error.DataError.ServerUnreachable("offline") }
        assertThat(repository.pushPending()).isFalse()
        assertThat(database.progressDao().get("movie-1")!!.syncState).isEqualTo(SyncState.FAILED.name)
    }

    @Test
    fun `continue watching shows one entry per show and skips finished items`() = runTest {
        val show = Fixtures.show()
        val movie = Fixtures.movie()
        database.libraryDao().upsertAll(listOf(show.toEntity(), movie.toEntity()))
        val episode1 = Fixtures.episode(episodeNumber = 1)
        val episode2 = Fixtures.episode(episodeNumber = 2)
        database.episodeDao().upsertAll(listOf(episode1.toEntity(), episode2.toEntity()))

        repository.save(movie.id, positionMs = 40 * 60_000, durationMs = 118 * 60_000)
        time.advance(1_000)
        repository.save(episode1.id, positionMs = 20 * 60_000, durationMs = 45 * 60_000, showId = show.id)
        time.advance(1_000)
        repository.save(episode2.id, positionMs = 5 * 60_000, durationMs = 45 * 60_000, showId = show.id)

        val cards = repository.observeContinueWatching().first()

        // The show contributes only its most recent episode; the movie is a separate entry.
        assertThat(cards.map { it.playableId }).containsExactly(episode2.id, movie.id).inOrder()
        assertThat(cards.first().episode?.episodeNumber).isEqualTo(2)

        // Finishing the movie removes it from the row.
        repository.save(movie.id, positionMs = 117 * 60_000, durationMs = 118 * 60_000)
        assertThat(repository.observeContinueWatching().first().map { it.playableId })
            .doesNotContain(movie.id)
    }

    @Test
    fun `removing an entry deletes it locally even when the server call fails`() = runTest {
        repository.save("movie-1", positionMs = 10_000, durationMs = 100_000)
        remote.failure = { com.myflix.core.common.error.DataError.ServerUnreachable("offline") }

        repository.remove("movie-1")

        assertThat(database.progressDao().get("movie-1")).isNull()
    }

    @Test
    fun `mark watched works for an item that was never played`() = runTest {
        repository.markWatched("movie-2", durationMs = 100_000, watched = true)
        val stored = database.progressDao().get("movie-2")!!
        assertThat(stored.watched).isTrue()
        assertThat(stored.positionMs).isEqualTo(100_000)

        repository.markWatched("movie-2", durationMs = 100_000, watched = false)
        assertThat(database.progressDao().get("movie-2")!!.watched).isFalse()
        assertThat(database.progressDao().get("movie-2")!!.positionMs).isEqualTo(0)
    }

    @Test
    fun `negative or oversized positions from a bad player never reach the database`() = runTest {
        repository.save("movie-3", positionMs = -5_000, durationMs = -1)
        val stored = database.progressDao().get("movie-3")!!
        assertThat(stored.positionMs).isEqualTo(0)
        assertThat(stored.durationMs).isEqualTo(0)
        assertThat(stored.watched).isFalse()
    }
}
