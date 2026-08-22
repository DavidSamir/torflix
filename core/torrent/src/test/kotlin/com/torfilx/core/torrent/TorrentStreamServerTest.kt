package com.torfilx.core.torrent

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Protocol-level tests for the loopback server that feeds ExoPlayer from a torrent.
 *
 * The bug these exist for: the server used to write its headers — including a `Content-Length` —
 * *before* checking whether any data was available, and then returned without writing a body when
 * libtorrent had not yet created the file. A body shorter than its declared length is a hard I/O
 * error to ExoPlayer, which surfaced as "Connection lost" on the first attempt at every new title
 * and went away on a later retry, once the file happened to exist. That is precisely the reported
 * "it says network error and I have to press retry three or four times".
 *
 * The invariant being pinned is simple and absolute: **if a status line promising N bytes has been
 * sent, N bytes are delivered.** Anything that cannot honour that must fail before the status line.
 */
class TorrentStreamServerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: TorrentStreamServer
    private lateinit var payload: ByteArray
    private lateinit var file: File

    @Before
    fun setUp() {
        // Deterministic, non-repeating content so a wrong offset cannot accidentally match.
        payload = ByteArray(8192) { (it % 251).toByte() }
        file = temp.newFile("movie.mkv")
        // Short timeouts: these tests exercise the give-up paths, and the production defaults are
        // tens of seconds.
        server = TorrentStreamServer(firstByteTimeoutMs = 1_000L, pieceTimeoutMs = 1_000L)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    /**
     * A stand-in for a torrent being downloaded.
     *
     * [availableBytes] is how much of the file has "arrived": reads past it are refused exactly as a
     * missing piece would be, which is what lets a partially-downloaded file be simulated without
     * libtorrent.
     */
    private class FakeSource(
        override val infoHash: String,
        override val filePath: File,
        override val fileSizeBytes: Long,
        val availableBytes: AtomicLong,
        val fileVisible: AtomicBoolean = AtomicBoolean(true),
    ) : StreamSource {
        override val fileName: String get() = filePath.name
        val prioritiseCalls = AtomicLong(0)

        override fun isReadableAt(fileByteOffset: Long): Boolean =
            fileVisible.get() && filePath.exists() && fileByteOffset < availableBytes.get()

        override fun bytesUntilPieceEnd(fileByteOffset: Long): Long =
            (availableBytes.get() - fileByteOffset).coerceAtLeast(1L)

        override fun prioritiseFrom(fileByteOffset: Long) {
            prioritiseCalls.incrementAndGet()
        }

        override fun touch() = Unit
    }

    private fun register(
        available: Long = payload.size.toLong(),
        visible: Boolean = true,
        writeFile: Boolean = true,
    ): FakeSource {
        if (writeFile) file.writeBytes(payload)
        val source = FakeSource(
            infoHash = "abc123",
            filePath = file,
            fileSizeBytes = payload.size.toLong(),
            availableBytes = AtomicLong(available),
            fileVisible = AtomicBoolean(visible),
        )
        server.register(source)
        return source
    }

    private fun url(path: String = "abc123/movie.mkv") = URL("http://127.0.0.1:${server.port}/$path")

    private fun open(path: String = "abc123/movie.mkv", range: String? = null): HttpURLConnection =
        (url(path).openConnection() as HttpURLConnection).apply {
            range?.let { setRequestProperty("Range", it) }
            connectTimeout = 5_000
            readTimeout = 10_000
        }

    /** Reads exactly [n] bytes or throws — a short body must fail the test, not be tolerated. */
    private fun HttpURLConnection.readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        DataInputStream(inputStream).use { it.readFully(out) }
        return out
    }

    @Test
    fun `a whole-file request returns the whole file`() {
        register()
        val connection = open()
        assertThat(connection.responseCode).isEqualTo(200)
        assertThat(connection.getHeaderField("Content-Length").toInt()).isEqualTo(payload.size)
        assertThat(connection.readExactly(payload.size)).isEqualTo(payload)
    }

    @Test
    fun `a range request returns exactly that range`() {
        register()
        val connection = open(range = "bytes=100-199")
        assertThat(connection.responseCode).isEqualTo(206)
        assertThat(connection.getHeaderField("Content-Range"))
            .isEqualTo("bytes 100-199/${payload.size}")
        assertThat(connection.readExactly(100)).isEqualTo(payload.copyOfRange(100, 200))
    }

    @Test
    fun `an open-ended range runs to the end of the file`() {
        register()
        val connection = open(range = "bytes=8000-")
        assertThat(connection.responseCode).isEqualTo(206)
        assertThat(connection.readExactly(192)).isEqualTo(payload.copyOfRange(8000, 8192))
    }

    @Test
    fun `a range past the end of the file is refused with 416`() {
        register()
        val connection = open(range = "bytes=99999-")
        assertThat(connection.responseCode).isEqualTo(416)
    }

    @Test
    fun `an unknown info hash is a 404`() {
        register()
        assertThat(open(path = "notregistered").responseCode).isEqualTo(404)
    }

    @Test
    fun `the file name segment is decoration and does not affect lookup`() {
        register()
        // Same torrent, different trailing segment: still resolves.
        assertThat(open(path = "abc123/something%20else.avi").responseCode).isEqualTo(200)
        assertThat(open(path = "abc123").responseCode).isEqualTo(200)
    }

    @Test
    fun `the content type follows the file extension rather than always claiming mp4`() {
        register()
        assertThat(open().getHeaderField("Content-Type")).isEqualTo("video/x-matroska")
    }

    @Test
    fun `HEAD returns headers without a body and without waiting for data`() {
        // Nothing has arrived at all: a HEAD must still answer immediately.
        register(available = 0, writeFile = false)
        val connection = open().apply { requestMethod = "HEAD" }
        assertThat(connection.responseCode).isEqualTo(200)
        assertThat(connection.getHeaderField("Content-Length").toInt()).isEqualTo(payload.size)
        assertThat(connection.getHeaderField("Accept-Ranges")).isEqualTo("bytes")
    }

    /**
     * The regression test for the reported failure.
     *
     * The file does not exist when the request arrives — exactly the state a brand-new title is in —
     * and appears a moment later. The response must be complete, not a 200 followed by silence.
     */
    @Test
    fun `a file that appears after the request still yields a complete body`() {
        val source = register(available = 0, writeFile = false)
        Thread {
            Thread.sleep(200)
            file.writeBytes(payload)
            source.availableBytes.set(payload.size.toLong())
        }.start()

        val connection = open()
        assertThat(connection.responseCode).isEqualTo(200)
        assertThat(connection.readExactly(payload.size)).isEqualTo(payload)
    }

    /**
     * When data genuinely never arrives, the failure must be a clean status code decided *before*
     * anything is committed to the socket — never a truncated body under a declared Content-Length.
     */
    @Test
    fun `a stalled swarm produces a 503 and no partial body`() {
        register(available = 0, writeFile = false)
        val connection = open()
        assertThat(connection.responseCode).isEqualTo(503)
        assertThat(connection.getHeaderField("Content-Length").toInt()).isEqualTo(0)
    }

    @Test
    fun `a request waits for a piece that is still downloading rather than truncating`() {
        // Half the file has arrived; the rest lands while the response is being written.
        val source = register(available = 4096)
        Thread {
            Thread.sleep(150)
            source.availableBytes.set(payload.size.toLong())
        }.start()

        val connection = open()
        assertThat(connection.responseCode).isEqualTo(200)
        assertThat(connection.readExactly(payload.size)).isEqualTo(payload)
    }

    @Test
    fun `serving prioritises the pieces around the read position`() {
        // Streaming only works because the read head drags piece priority with it; if this stops
        // happening the swarm fetches in rarest-first order and playback starves.
        val source = register()
        open(range = "bytes=4096-").readExactly(payload.size - 4096)
        assertThat(source.prioritiseCalls.get()).isGreaterThan(0L)
    }

    @Test
    fun `concurrent requests are served independently`() {
        register()
        val results = List(4) { index ->
            val thread = Thread {
                val connection = open(range = "bytes=${index * 1000}-${index * 1000 + 499}")
                check(connection.responseCode == 206)
                check(connection.readExactly(500)
                    .contentEquals(payload.copyOfRange(index * 1000, index * 1000 + 500)))
            }
            thread.start()
            thread
        }
        results.forEach { it.join(10_000) }
        results.forEach { assertThat(it.isAlive).isFalse() }
    }
}
