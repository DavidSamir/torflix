package com.torfilx.core.torrent

import com.torfilx.core.common.log.TorfilxLog
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.min

private const val TAG = "TorrentHttp"

/** Piece index (clamped to the torrent) that holds [fileByteOffset] of a file at [fileOffset]. */
internal fun pieceIndexOf(fileOffset: Long, pieceLength: Int, numPieces: Int, fileByteOffset: Long): Int =
    ((fileOffset + fileByteOffset) / pieceLength).toInt().coerceIn(0, numPieces - 1)

/**
 * Bytes from [fileByteOffset] to the end of the piece that contains it — the maximum a single read
 * may cover without spilling into the following piece. Pure arithmetic, unit-tested, because getting
 * it wrong feeds zero-filled holes to the decoder.
 */
internal fun bytesUntilPieceEnd(fileOffset: Long, pieceLength: Int, fileByteOffset: Long): Long {
    val absolute = fileOffset + fileByteOffset
    val nextBoundary = ((absolute / pieceLength) + 1) * pieceLength.toLong()
    return (nextBoundary - absolute).coerceAtLeast(1L)
}

/** One torrent being streamed, plus the piece bookkeeping that makes seeking work. */
internal class StreamedTorrent(
    val infoHash: String,
    val handle: TorrentHandle,
    val fileIndex: Int,
    val fileName: String,
    val fileSizeBytes: Long,
    val filePath: File,
    val pieceLength: Int,
    val fileOffset: Long,
    val numPieces: Int,
    var lastTouchedMs: Long,
) {

    /**
     * False once playback has stopped but the torrent is still in the session, seeding.
     *
     * Kept in the same map as streaming torrents so seeding shows up in the sharing stats and is
     * still governed by the storage budget — a torrent that seeds invisibly is one nobody can
     * manage.
     */
    @Volatile
    var isStreaming: Boolean = true

    fun toStream(port: Int) = TorrentStream(
        infoHash = infoHash,
        url = "http://127.0.0.1:$port/$infoHash",
        fileName = fileName,
        fileSizeBytes = fileSizeBytes,
    )

    /** Piece index containing [fileByteOffset] of the selected file. */
    fun pieceOf(fileByteOffset: Long): Int =
        pieceIndexOf(fileOffset, pieceLength, numPieces, fileByteOffset)

    fun hasByte(fileByteOffset: Long): Boolean =
        runCatching { handle.havePiece(pieceOf(fileByteOffset)) }.getOrDefault(false)

    /**
     * Bytes from [fileByteOffset] to the end of the piece that contains it.
     *
     * A read is capped to this so it never spans into the next piece: `havePiece` is verified for
     * the piece at the read position, but the following piece may still be a hole in the sparse file
     * that would read back as zeros and feed corrupt data to the decoder.
     */
    fun bytesUntilPieceEnd(fileByteOffset: Long): Long =
        bytesUntilPieceEnd(fileOffset, pieceLength, fileByteOffset)

    /**
     * Prioritises the pieces just after [fileByteOffset].
     *
     * This is what turns BitTorrent (which normally fetches rarest-first, in any order) into
     * something streamable: a deadline on the next few pieces and descending priority after that.
     */
    fun prioritiseFrom(fileByteOffset: Long) {
        runCatching {
            val first = pieceOf(fileByteOffset)
            val last = min(first + READ_AHEAD_PIECES, numPieces - 1)
            for (piece in first..last) {
                if (handle.havePiece(piece)) continue
                val distance = piece - first
                handle.piecePriority(
                    piece,
                    when {
                        distance < URGENT_PIECES -> Priority.TOP_PRIORITY
                        distance < URGENT_PIECES * 2 -> Priority.SIX
                        else -> Priority.FIVE
                    },
                )
                // A deadline tells libtorrent to fetch this piece within N ms or complain, which is
                // what keeps playback ahead of the download on a slow swarm.
                handle.setPieceDeadline(piece, distance * DEADLINE_STEP_MS)
            }
        }.onFailure { TorfilxLog.w(TAG, "Could not prioritise pieces", it) }
    }

    private companion object {
        const val READ_AHEAD_PIECES = 40
        const val URGENT_PIECES = 6
        const val DEADLINE_STEP_MS = 500
    }
}

/**
 * A minimal loopback HTTP server that serves a torrent's video file *while it downloads*.
 *
 * ExoPlayer speaks HTTP with byte ranges; BitTorrent delivers pieces. This bridges the two: a read
 * that lands on a missing piece re-prioritises around it and waits, rather than failing, so seeking
 * into an undownloaded part of the film works instead of erroring out.
 *
 * It binds to 127.0.0.1 only — nothing outside the device can reach it.
 */
internal class TorrentStreamServer {

    private val streams = ConcurrentHashMap<String, StreamedTorrent>()
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        thread(name = "torfilx-torrent-http", isDaemon = true) { acceptLoop(socket) }
        TorfilxLog.i(TAG, "Loopback stream server on 127.0.0.1:$port")
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        streams.clear()
        executor.shutdownNow()
    }

    fun register(streamed: StreamedTorrent) {
        streams[streamed.infoHash] = streamed
    }

    fun unregister(infoHash: String) {
        streams.remove(infoHash)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (closed: SocketException) {
                return
            } catch (error: IOException) {
                TorfilxLog.w(TAG, "Accept failed", error)
                continue
            }
            executor.execute { runCatching { handle(client) }.onFailure { logClientFailure(it) } }
        }
    }

    private fun logClientFailure(error: Throwable) {
        // A player closing the connection mid-stream is normal (seek, exit) and not worth an error.
        if (error is SocketException || error is IOException) {
            TorfilxLog.d(TAG, "Client disconnected: ${error.message}")
        } else {
            TorfilxLog.w(TAG, "Stream request failed", error)
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].trimStart('/')

            var rangeStart = 0L
            var rangeEnd = -1L
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    // Only byte ranges are understood; any other unit is ignored (served as full).
                    val value = line.substringAfter(':').trim()
                    if (value.startsWith("bytes=", ignoreCase = true)) {
                        val spec = value.substringAfter('=').trim()
                        rangeStart = (spec.substringBefore('-').toLongOrNull() ?: 0L).coerceAtLeast(0L)
                        rangeEnd = spec.substringAfter('-').toLongOrNull() ?: -1L
                    }
                }
            }

            val streamed = streams[path]
            val output = BufferedOutputStream(socket.getOutputStream())
            if (streamed == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            val total = streamed.fileSizeBytes
            val rangeRequested = rangeStart > 0 || rangeEnd >= 0

            // A range starting at or past the end of the file is unsatisfiable. Answer 416 rather
            // than a technically-invalid 0-length 206, which some ExoPlayer data sources treat as a
            // hard error.
            if (rangeRequested && rangeStart >= total) {
                output.write(
                    (
                        "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                            "Content-Range: bytes */$total\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        ).toByteArray(),
                )
                output.flush()
                return
            }

            val end = if (rangeEnd in 0 until total) rangeEnd else total - 1
            val length = (end - rangeStart + 1).coerceAtLeast(0)

            val headers = buildString {
                append(if (rangeRequested) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: video/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (rangeRequested) {
                    append("Content-Range: bytes $rangeStart-$end/$total\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            output.write(headers.toByteArray())
            if (method.equals("HEAD", ignoreCase = true)) {
                output.flush()
                return
            }

            streamed.prioritiseFrom(rangeStart)
            serveBytes(streamed, rangeStart, length, output)
        }
    }

    private fun serveBytes(
        streamed: StreamedTorrent,
        start: Long,
        length: Long,
        output: BufferedOutputStream,
    ) {
        val file = streamed.filePath
        if (!file.exists()) {
            TorfilxLog.w(TAG, "Torrent file not on disk yet: ${file.name}")
            return
        }

        val buffer = ByteArray(CHUNK_BYTES)
        var position = start
        var remaining = length

        // One handle for the whole response: re-opening it for every 64 KB chunk was hundreds of
        // thousands of syscalls per movie on a weak Fire TV CPU.
        RandomAccessFile(file, "r").use { raf ->
            while (remaining > 0) {
                if (!awaitPiece(streamed, position)) {
                    TorfilxLog.w(TAG, "Timed out waiting for piece at $position; ending stream")
                    return
                }
                // Re-prioritise as playback advances so the swarm stays ahead of the read head.
                if ((position - start) % PRIORITISE_EVERY_BYTES < CHUNK_BYTES) {
                    streamed.prioritiseFrom(position)
                }

                // Never read past the end of the piece just confirmed present: a read that crossed
                // into the next (possibly not-yet-downloaded) piece would return zero-filled holes
                // from the sparse file and hand corrupt bytes to the decoder.
                val toRead = minOf(remaining, CHUNK_BYTES.toLong(), streamed.bytesUntilPieceEnd(position))
                val fileLen = raf.length()
                if (fileLen <= position) {
                    // Data verified present but not yet flushed to the file; wait briefly and retry.
                    Thread.sleep(WAIT_STEP_MS)
                    continue
                }
                val readable = min(toRead, fileLen - position).toInt()
                raf.seek(position)
                val read = raf.read(buffer, 0, readable)
                if (read <= 0) {
                    Thread.sleep(WAIT_STEP_MS)
                    continue
                }
                output.write(buffer, 0, read)
                output.flush()
                position += read
                remaining -= read
                streamed.lastTouchedMs = System.currentTimeMillis()
            }
        }
    }

    /** Blocks until the piece holding [position] has arrived, or gives up after the timeout. */
    private fun awaitPiece(streamed: StreamedTorrent, position: Long): Boolean {
        if (streamed.hasByte(position)) return true
        streamed.prioritiseFrom(position)
        var waited = 0L
        while (waited < PIECE_TIMEOUT_MS) {
            Thread.sleep(WAIT_STEP_MS)
            waited += WAIT_STEP_MS
            if (streamed.hasByte(position)) return true
        }
        return false
    }

    private companion object {
        const val BACKLOG = 8
        const val CHUNK_BYTES = 64 * 1024
        const val SOCKET_TIMEOUT_MS = 30_000
        const val WAIT_STEP_MS = 100L
        const val PIECE_TIMEOUT_MS = 120_000L
        const val PRIORITISE_EVERY_BYTES = 4L * 1024 * 1024
    }
}
