package com.torfilx.core.torrent

import android.content.Context
import android.os.StatFs
import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.common.log.TorfilxLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.swig.settings_pack
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Torrent"

/**
 * BitTorrent engine built on libtorrent4j.
 *
 * Streaming, rather than downloading-then-playing, is the whole point: the file the user picked is
 * the only one downloaded, its pieces are prioritised in play order, and the first pieces get a
 * deadline so playback can start in seconds.
 *
 * Sharing is deliberate: nothing is uploaded until [consentProvider] says the user opted in, and the
 * data kept for seeding never exceeds the space [storageBudget] allows.
 */
@Singleton
class LibTorrentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consentProvider: SharingConsentProvider,
    @Dispatcher(TorfilxDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) : TorrentEngine {

    /**
     * Created on first use, never in the constructor.
     *
     * Constructing a [SessionManager] runs libtorrent's static initialiser, which loads
     * `libtorrent4j.so`. Hilt builds this singleton on the main thread during `MainActivity.onCreate`,
     * so a device whose ABI is missing from the APK — or whose loader rejects the library — used to
     * die with a `LinkageError` before a single frame was drawn, with no way to catch it.
     */
    private val sessionRef: SessionManager by lazy { SessionManager() }

    /** Turns any native-loading failure into the app's own typed error instead of a [LinkageError]. */
    private val session: SessionManager
        get() = runCatching { sessionRef }.getOrElse { throw TorrentError.EngineUnavailable(it) }
    private val sessionMutex = Mutex()
    private val streamServer = TorrentStreamServer()
    /** Streaming *and* seeding torrents; a torrent leaves this map only when it is removed. */
    private val managedTorrents = java.util.concurrent.ConcurrentHashMap<String, StreamedTorrent>()

    private val _torrents = MutableStateFlow<List<TorrentStatus>>(emptyList())
    override val torrents: Flow<List<TorrentStatus>> = _torrents.asStateFlow()

    override val stats: Flow<SharingStats> = _torrents.map { list -> aggregate(list) }

    @Volatile
    private var nativeAvailable: Boolean? = null

    @Volatile
    private var started = false

    /**
     * Where torrent data lives: internal app storage.
     *
     * Deliberately *not* `getExternalFilesDir`: that path is served by the MediaProvider FUSE daemon,
     * which the engine.s random-access reads and writes can crash — taking the whole app down with
     * it when the platform then kills every process touching the volume. Internal storage is plain
     * ext4, app-private, invisible to the media scanner, and removed on uninstall.
     */
    private val downloadDir: File by lazy {
        File(context.filesDir, "torrents").apply { mkdirs() }
    }

    override fun isAvailable(): Boolean {
        nativeAvailable?.let { return it }
        val available = runCatching {
            // Touching a native-backed class is the only reliable way to know the .so loaded.
            org.libtorrent4j.LibTorrent.version()
            true
        }.getOrElse {
            TorfilxLog.e(TAG, "libtorrent native library unavailable", it)
            false
        }
        nativeAvailable = available
        return available
    }

    override suspend fun start() = withContext(ioDispatcher) {
        sessionMutex.withLock {
            if (started) return@withLock
            if (!isAvailable()) throw TorrentError.EngineUnavailable(null)

            val settings = SettingsPack().apply {
                // Modest limits: a Fire Stick has a weak CPU and shares the household Wi-Fi.
                setInteger(settings_pack.int_types.active_downloads.swigValue(), MAX_ACTIVE_DOWNLOADS)
                setInteger(settings_pack.int_types.active_seeds.swigValue(), MAX_ACTIVE_SEEDS)
                setInteger(settings_pack.int_types.connections_limit.swigValue(), CONNECTION_LIMIT)
                setInteger(settings_pack.int_types.alert_queue_size.swigValue(), ALERT_QUEUE_SIZE)
                setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
                setString(settings_pack.string_types.user_agent.swigValue(), USER_AGENT)
                // Upload is only allowed once the user has consented to sharing.
                setInteger(
                    settings_pack.int_types.upload_rate_limit.swigValue(),
                    if (consentProvider.hasConsented()) 0 else 1,
                )
            }

            runCatching { session.start(SessionParams(settings)) }
                .onFailure { throw TorrentError.EngineUnavailable(it) }

            started = true
            streamServer.start()
            startStatusPolling()
            TorfilxLog.i(TAG, "Torrent session started (dir=${downloadDir.absolutePath})")
        }
    }

    override suspend fun stop() = withContext(ioDispatcher) {
        sessionMutex.withLock {
            if (!started) return@withLock
            streamServer.stop()
            managedTorrents.clear()
            runCatching { session.stop() }
            started = false
            TorfilxLog.i(TAG, "Torrent session stopped")
        }
    }

    override suspend fun stream(magnet: String): TorrentStream = withContext(ioDispatcher) {
        if (!consentProvider.hasConsented()) throw TorrentError.NotConsented()
        val infoHash = MagnetLink.infoHashOf(magnet) ?: throw TorrentError.InvalidMagnet(magnet)

        start()
        enforceStorageBudget()

        managedTorrents[infoHash]?.let { existing ->
            // Already in the session (possibly seeding): resume streaming from it rather than
            // re-adding the torrent and re-checking every piece on disk.
            existing.isStreaming = true
            streamServer.register(existing)
            return@withContext existing.toStream(streamServer.port)
        }

        session.download(magnet, downloadDir, org.libtorrent4j.swig.torrent_flags_t())

        // Metadata arrives from peers; without it there is nothing to prioritise or serve.
        val handle = withTimeoutOrNull(METADATA_TIMEOUT_MS) {
            var found: TorrentHandle? = null
            while (found == null) {
                val candidate = session.find(infoHash.toSha1Hash())
                if (candidate != null && candidate.isValid && candidate.torrentFile() != null) {
                    found = candidate
                }
                delay(POLL_INTERVAL_MS)
            }
            found
        } ?: throw TorrentError.MetadataTimeout()

        val info = handle.torrentFile() ?: throw TorrentError.MetadataTimeout()
        val files = info.files()

        // The video is the largest file; sample/subtitle files are ignored entirely.
        val fileIndex = (0 until files.numFiles())
            .filter { files.fileName(it).isVideoFile() }
            .maxByOrNull { files.fileSize(it) }
            ?: throw TorrentError.NoPlayableFile()

        val fileSize = files.fileSize(fileIndex)
        val free = freeSpaceBytes()
        if (fileSize > storageBudget.capBytes(free)) {
            TorfilxLog.w(
                TAG,
                "File (${fileSize / 1_000_000} MB) exceeds the storage budget; " +
                    "streaming with aggressive eviction",
            )
        }

        // Download only the chosen file.
        val priorities = Array(files.numFiles()) { Priority.IGNORE }
        priorities[fileIndex] = Priority.DEFAULT
        handle.prioritizeFiles(priorities)
        handle.resume()

        val streamed = StreamedTorrent(
            infoHash = infoHash,
            handle = handle,
            fileIndex = fileIndex,
            fileName = files.fileName(fileIndex),
            fileSizeBytes = fileSize,
            filePath = File(downloadDir, files.filePath(fileIndex)),
            pieceLength = info.pieceLength(),
            fileOffset = files.fileOffset(fileIndex),
            numPieces = info.numPieces(),
            lastTouchedMs = System.currentTimeMillis(),
        )
        managedTorrents[infoHash] = streamed
        streamServer.register(streamed)

        // Prime the beginning of the file so playback can start without waiting for the whole thing.
        streamed.prioritiseFrom(0L)

        streamed.toStream(streamServer.port)
    }

    override suspend fun stopStreaming(infoHash: String) = withContext(ioDispatcher) {
        val streamed = managedTorrents[infoHash] ?: return@withContext
        streamServer.unregister(infoHash)
        streamed.isStreaming = false
        // The torrent stays in the session so it keeps seeding what was downloaded; without consent
        // it is paused instead, which stops all upload immediately.
        if (consentProvider.hasConsented()) {
            runCatching { streamed.handle.resume() }
            TorfilxLog.i(TAG, "Now seeding ${streamed.fileName}")
        } else {
            runCatching { streamed.handle.pause() }
        }
        enforceStorageBudget()
    }

    override suspend fun remove(infoHash: String, deleteData: Boolean) = withContext(ioDispatcher) {
        streamServer.unregister(infoHash)
        managedTorrents.remove(infoHash)
        val handle = runCatching { session.find(infoHash.toSha1Hash()) }.getOrNull()
        if (handle != null && handle.isValid) {
            runCatching {
                if (deleteData) {
                    session.remove(handle, org.libtorrent4j.swig.session_handle.delete_files)
                } else {
                    session.remove(handle)
                }
            }.onFailure { TorfilxLog.w(TAG, "Could not remove torrent $infoHash", it) }
        }
    }

    /**
     * Keeps disk usage inside the budget.
     *
     * Eviction is oldest-touched-first and never removes what is currently playing, so a long
     * seeding session can never cost the user the film they are watching.
     */
    override suspend fun enforceStorageBudget() = withContext(ioDispatcher) {
        val free = freeSpaceBytes()
        val cap = storageBudget.capBytes(free)
        var used = directorySize(downloadDir)
        if (used <= cap) return@withContext

        TorfilxLog.i(TAG, "Torrent data ${used / 1_000_000} MB over cap ${cap / 1_000_000} MB; evicting")

        val candidates = _torrents.value
            .filterNot { managedTorrents[it.infoHash]?.isStreaming == true }
            .sortedBy { lastTouched[it.infoHash] ?: 0L }

        for (candidate in candidates) {
            if (used <= cap) break
            remove(candidate.infoHash, deleteData = true)
            used = directorySize(downloadDir)
        }

        if (used > cap) {
            TorfilxLog.w(TAG, "Still over the storage cap after eviction (${used / 1_000_000} MB)")
        }
    }

    /** Applies a consent change immediately: uploading is throttled to nothing without consent. */
    fun onConsentChanged(consented: Boolean) {
        if (!started) return
        runCatching {
            session.applySettings(
                SettingsPack().apply {
                    setInteger(
                        settings_pack.int_types.upload_rate_limit.swigValue(),
                        if (consented) 0 else 1,
                    )
                },
            )
            if (!consented) {
                _torrents.value.forEach { status ->
                    session.find(status.infoHash.toSha1Hash())
                        ?.takeIf { it.isValid && status.isSeeding }
                        ?.pause()
                }
            }
        }.onFailure { TorfilxLog.w(TAG, "Could not apply consent change", it) }
    }

    /** The budget in force; overridable from Settings. */
    @Volatile
    var storageBudget: StorageBudget = StorageBudget.DEFAULT

    private val lastTouched = mutableMapOf<String, Long>()

    private fun startStatusPolling() {
        scope.launch {
            while (isActive && started) {
                val snapshot = runCatching { collectStatuses() }.getOrDefault(emptyList())
                _torrents.value = snapshot
                snapshot.forEach { lastTouched.putIfAbsent(it.infoHash, System.currentTimeMillis()) }
                managedTorrents.values.filter { it.isStreaming }
                    .forEach { lastTouched[it.infoHash] = System.currentTimeMillis() }
                delay(STATUS_POLL_MS)
            }
        }
    }

    private fun collectStatuses(): List<TorrentStatus> = managedTorrents.values.mapNotNull { streamed ->
        val handle = streamed.handle
        if (!handle.isValid) return@mapNotNull null
        val status = handle.status()
        TorrentStatus(
            infoHash = streamed.infoHash,
            name = streamed.fileName,
            progress = status.progress(),
            downloadRateBytesPerSecond = status.downloadRate(),
            uploadRateBytesPerSecond = status.uploadRate(),
            peers = status.numPeers(),
            seeds = status.numSeeds(),
            totalDownloadedBytes = status.totalDownload(),
            totalUploadedBytes = status.totalUpload(),
            isSeeding = status.isSeeding,
            isPaused = status.state() == org.libtorrent4j.TorrentStatus.State.UNKNOWN ||
                status.flags().and_(org.libtorrent4j.TorrentFlags.PAUSED).to_int() != 0,
            hasMetadata = handle.torrentFile() != null,
            sizeBytes = streamed.fileSizeBytes,
        )
    }

    private fun aggregate(list: List<TorrentStatus>): SharingStats {
        val free = freeSpaceBytes()
        return SharingStats(
            activeTorrents = list.size,
            downloadRateBytesPerSecond = list.sumOf { it.downloadRateBytesPerSecond },
            uploadRateBytesPerSecond = list.sumOf { it.uploadRateBytesPerSecond },
            totalUploadedBytes = list.sumOf { it.totalUploadedBytes },
            totalDownloadedBytes = list.sumOf { it.totalDownloadedBytes },
            diskUsedBytes = directorySize(downloadDir),
            diskCapBytes = storageBudget.capBytes(free),
            freeSpaceBytes = free,
            isSeeding = list.any { it.isSeeding && !it.isPaused },
        )
    }

    private fun freeSpaceBytes(): Long = runCatching {
        val stat = StatFs(downloadDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    private fun directorySize(dir: File): Long = runCatching {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun String.isVideoFile(): Boolean {
        val lower = lowercase()
        return VIDEO_EXTENSIONS.any { lower.endsWith(it) }
    }

    private companion object {
        const val MAX_ACTIVE_DOWNLOADS = 2
        const val MAX_ACTIVE_SEEDS = 4
        const val CONNECTION_LIMIT = 120
        const val ALERT_QUEUE_SIZE = 2_000
        const val USER_AGENT = "Torfilx/1.0 libtorrent/2.x"
        const val METADATA_TIMEOUT_MS = 60_000L
        const val POLL_INTERVAL_MS = 250L
        const val STATUS_POLL_MS = 1_000L
        val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".mpg", ".mpeg")
    }
}

/** Supplies the user's sharing decision to the engine without the engine knowing about settings. */
interface SharingConsentProvider {
    fun hasConsented(): Boolean
}
