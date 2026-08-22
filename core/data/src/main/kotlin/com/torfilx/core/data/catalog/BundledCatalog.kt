package com.torfilx.core.data.catalog

import android.content.Context
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.HdrType
import com.torfilx.core.model.Images
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaSource
import com.torfilx.core.model.SourceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Catalog"
private const val ASSET_NAME = "catalog.json"

@Serializable
private data class CatalogEntryDto(
    val title: String = "",
    val year: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val magnets: List<CatalogMagnetDto> = emptyList(),
)

@Serializable
private data class CatalogMagnetDto(
    val quality: String? = null,
    val magnet: String = "",
)

/** A catalogue entry with its playable magnets, after validation. */
data class CatalogItem(
    val item: MediaItem,
    val sources: List<MediaSource>,
)

/**
 * The catalogue that ships inside the app.
 *
 * This is the only source of films in the app. Entries whose magnet links are malformed are dropped
 * at load time and logged — a broken magnet must never reach the player as a mysterious failure.
 */
@Singleton
class BundledCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    @Volatile
    private var cached: Loaded? = null

    /**
     * Everything derived from the catalogue file, computed once.
     *
     * With a few thousand titles the difference matters: a per-id linear scan and a fresh
     * `map { it.item }` on every flow emission turn a progress tick (every 10 s while playing) into
     * thousands of allocations, on a device with a very small CPU budget.
     */
    private class Loaded(
        val items: List<CatalogItem>,
        /** Domain items in catalogue order, so rows never re-map the list. */
        val mediaItems: List<MediaItem>,
        val byId: Map<String, CatalogItem>,
        val genres: List<String>,
        /** Lower-cased titles, parallel to [mediaItems], so search does no per-keystroke lowercasing. */
        val searchKeys: List<String>,
    )

    /**
     * The parsed catalogue, built once — but **only a good result is kept**.
     *
     * A failed or short read used to be cached like any other outcome, which meant one bad read at
     * startup left the app with a broken library for the entire life of the process, with no way
     * back except force-stopping it. Since the read is the thing that has been going wrong, an
     * incomplete result is now retried on the next access instead of being frozen in.
     */
    private fun loaded(): Loaded = cached ?: synchronized(this) {
        cached ?: buildLoaded().also { built ->
            val complete = built.items.isNotEmpty() &&
                (declaredCount == 0 || built.items.size >= declaredCount)
            if (complete) {
                cached = built
            } else {
                TorfilxLog.w(
                    TAG,
                    "Not caching an incomplete catalogue (${built.items.size} of $declaredCount); " +
                        "the next read will try again",
                )
            }
        }
    }

    /** Parses the asset eagerly, off the main thread. Safe to call more than once. */
    fun preload() {
        loaded()
    }

    fun items(): List<CatalogItem> = loaded().items

    /** Domain items only — the list every screen actually renders. */
    fun mediaItems(): List<MediaItem> = loaded().mediaItems

    fun item(id: String): CatalogItem? = loaded().byId[id]

    fun sourcesFor(id: String): List<MediaSource> = item(id)?.sources.orEmpty()

    fun genres(): List<String> = loaded().genres

    /** Case-insensitive title search over pre-lowered keys. */
    fun search(query: String, limit: Int): List<MediaItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val data = loaded()
        val prefix = ArrayList<MediaItem>(limit)
        val contains = ArrayList<MediaItem>(limit)
        for (index in data.searchKeys.indices) {
            val key = data.searchKeys[index]
            when {
                key.startsWith(needle) -> prefix += data.mediaItems[index]
                key.contains(needle) -> contains += data.mediaItems[index]
                else -> continue
            }
            if (prefix.size >= limit) break
        }
        return (prefix + contains).take(limit)
    }

    private fun buildLoaded(): Loaded {
        val items = load()
        val mediaItems = items.map { it.item }
        return Loaded(
            items = items,
            mediaItems = mediaItems,
            byId = items.associateBy { it.item.id },
            genres = mediaItems.flatMap { it.genres }.distinct().sorted(),
            searchKeys = mediaItems.map { it.title.lowercase() },
        )
    }

    /** Titles the file declares, from the raw bytes — independent of the parser. */
    @Volatile
    var declaredCount: Int = 0
        private set

    /** True when fewer titles were parsed than the file declares. The library is incomplete. */
    val isIncomplete: Boolean get() = declaredCount > 0 && loaded().items.size < declaredCount

    /** Titles the file declares, so a screen can say "N of M" rather than just "N". */
    fun declaredTitleCount(): Int {
        loaded()
        return declaredCount
    }

    /**
     * Reads the asset **fully** before parsing, and checks the result against the file.
     *
     * Both halves of that matter, and both exist because a partial catalogue is invisible.
     *
     * Reading fully first: the parser used to decode straight off `AssetManager`'s stream. That
     * stream is not a file — for a compressed asset it is an inflater, it can hand back short reads,
     * and on the older Fire OS releases this app supports it can fail outright part-way through a
     * multi-megabyte entry. `readBytes()` loops until end-of-stream, so either every byte arrives or
     * it throws; there is no middle state where the parser sees a clean end-of-input that is really a
     * truncated read. (The asset is also packaged uncompressed now — see `noCompress` in the app
     * build file — which removes the inflater from the path altogether. This is the belt to that
     * pair of braces.)
     *
     * Checking afterwards: [parseCatalogStream] is deliberately resilient — it keeps whatever it
     * decoded before an error and stops quietly — which is right for one bad entry in a hand-edited
     * file and completely wrong as a silent outcome. Counting what the file *declares* costs one
     * pass over 2 MB, once, and turns "the app has fewer films than it should" from something only a
     * viewer can notice into something the app states.
     */
    private fun load(): List<CatalogItem> = runCatching {
        val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
        declaredCount = countDeclaredTitles(bytes)
        TorfilxLog.i(TAG, "Catalogue asset read: ${bytes.size} bytes, $declaredCount titles declared")

        val items = parseCatalogStream(bytes.inputStream(), json)
        if (declaredCount > 0 && items.size < declaredCount) {
            TorfilxLog.e(
                TAG,
                "CATALOGUE INCOMPLETE: parsed ${items.size} of $declaredCount declared titles",
            )
        }
        items
    }.getOrElse { error ->
        // A hard read failure now surfaces as an empty catalogue, which the Home screen states
        // outright, rather than as a handful of titles that looks like a small library.
        TorfilxLog.e(TAG, "Bundled catalogue could not be read", error)
        emptyList()
    }
}

/**
 * Counts `"title"` keys in the raw bytes.
 *
 * Deliberately not JSON-aware: the whole point is to have a number the parser cannot influence, so
 * that "the parser stopped early" is detectable. Scans bytes rather than decoding to a string, which
 * avoids a second multi-megabyte allocation on a small heap.
 */
internal fun countDeclaredTitles(bytes: ByteArray): Int {
    val needle = "\"title\"".toByteArray()
    var count = 0
    var index = 0
    outer@ while (index <= bytes.size - needle.size) {
        for (offset in needle.indices) {
            if (bytes[index + offset] != needle[offset]) {
                index++
                continue@outer
            }
        }
        count++
        index += needle.size
    }
    return count
}

/**
 * Streams the catalogue JSON array, mapping and validating each entry as it arrives.
 *
 * Streaming rather than reading the whole 2 MB file into a string plus a full DTO list keeps peak
 * memory low on a ~128 MB-heap stick. It is also far more resilient: a malformed entry is skipped
 * individually, and if a JSON syntax error stops the stream part-way, the entries parsed *before* it
 * are kept — a hand-edited catalogue with one broken line loses that line, not the whole library,
 * which used to collapse to an empty catalogue.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal fun parseCatalogStream(stream: java.io.InputStream, json: Json): List<CatalogItem> {
    val usedIds = HashSet<String>()
    val items = ArrayList<CatalogItem>()
    var index = 0
    try {
        json.decodeToSequence<CatalogEntryDto>(stream).forEach { entry ->
            runCatching { mapCatalogEntry(entry, index, usedIds) }
                .onFailure { TorfilxLog.w(TAG, "Catalogue entry $index skipped: ${it.message}") }
                .getOrNull()
                ?.let { items.add(it) }
            index++
        }
    } catch (error: Throwable) {
        // A JSON syntax error mid-stream: keep everything parsed so far instead of losing it all.
        // Logged as an error, not a warning: a partial catalogue is the app quietly shipping a
        // fraction of its content, and it looks exactly like a small library from the sofa.
        TorfilxLog.e(TAG, "Catalogue parse STOPPED EARLY after $index entries -- the library is incomplete", error)
    }
    TorfilxLog.i(TAG, "Bundled catalogue: ${items.size} titles")
    return items
}

/** Convenience for tests: parse a JSON string with the same resilient streaming path. */
internal fun parseCatalog(raw: String, json: Json): List<CatalogItem> =
    parseCatalogStream(raw.byteInputStream(), json)

/** Maps one decoded entry to a validated item, or null when it has no usable title. */
private fun mapCatalogEntry(
    entry: CatalogEntryDto,
    index: Int,
    usedIds: MutableSet<String>,
): CatalogItem? {
    val title = entry.title.trim()
    if (title.isEmpty()) {
        TorfilxLog.w(TAG, "Catalogue entry $index has no title; skipped")
        return null
    }

    val sources = entry.magnets.mapIndexedNotNull { magnetIndex, magnet ->
        val infoHash = com.torfilx.core.torrent.MagnetLink.infoHashOf(magnet.magnet)
        if (infoHash == null) {
            TorfilxLog.w(TAG, "\"$title\": magnet ${magnetIndex + 1} is malformed; skipped")
            return@mapIndexedNotNull null
        }
        val quality = magnet.quality?.trim().orEmpty()
        MediaSource(
            id = "torrent-$infoHash",
            kind = SourceKind.TORRENT,
            url = magnet.magnet,
            magnetUri = magnet.magnet,
            label = if (quality.isEmpty()) "Torrent" else "Torrent · $quality",
            videoCodec = null,
            height = quality.qualityHeight(),
            hdr = HdrType.NONE,
        )
    }

    // Ids must be unique: they are Compose list keys, and a duplicate key crashes the row. Two
    // entries can legitimately share a title and year (a re-release, a duplicate line in a
    // hand-edited file), so collisions are disambiguated by info hash, then by position.
    val baseId = "catalog-${title.slug()}-${entry.year.orEmpty()}"
    val id = when {
        usedIds.add(baseId) -> baseId
        else -> {
            val hashSuffix = sources.firstOrNull()?.id?.removePrefix("torrent-")?.take(8)
            val candidate = if (hashSuffix != null) "$baseId-$hashSuffix" else "$baseId-$index"
            if (usedIds.add(candidate)) candidate else "$baseId-$index".also { usedIds.add(it) }
        }
    }
    return CatalogItem(
        item = MediaItem(
            id = id,
            title = title,
            sortTitle = title.removePrefix("The ").trim(),
            year = entry.year?.filter { it.isDigit() }?.toIntOrNull(),
            runtimeMs = entry.runtimeMinutes?.let { it * 60_000L },
            overview = entry.overview,
            // A repeated genre would put the same film twice in one row, and a duplicate key inside a
            // lazy list is a hard crash — so genres are normalised here, once.
            genres = entry.genres
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .distinctBy { it.lowercase() },
            images = Images(poster = entry.imageUrl, backdrop = entry.imageUrl),
            addedAtMs = null,
            updatedAtMs = 0L,
        ),
        sources = sources,
    )
}

private fun String.slug(): String = lowercase()
    .map { if (it.isLetterOrDigit()) it else '-' }
    .joinToString("")
    .replace(Regex("-+"), "-")
    .trim('-')

private fun String.qualityHeight(): Int? = when {
    contains("2160") || contains("4k", ignoreCase = true) -> 2160
    contains("1080") -> 1080
    contains("720") -> 720
    contains("480") -> 480
    else -> null
}
