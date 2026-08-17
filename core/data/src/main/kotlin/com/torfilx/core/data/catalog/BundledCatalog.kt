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

    private fun loaded(): Loaded = cached ?: synchronized(this) {
        cached ?: buildLoaded().also { cached = it }
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

    private fun load(): List<CatalogItem> = runCatching {
        context.assets.open(ASSET_NAME).use { parseCatalogStream(it, json) }
    }.getOrElse { error ->
        TorfilxLog.e(TAG, "Bundled catalogue could not be read", error)
        emptyList()
    }
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
        TorfilxLog.w(TAG, "Catalogue parse stopped after $index entries: ${error.message}")
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
