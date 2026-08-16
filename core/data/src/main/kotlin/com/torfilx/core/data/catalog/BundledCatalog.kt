package com.torfilx.core.data.catalog

import android.content.Context
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.HdrType
import com.torfilx.core.model.Images
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaSource
import com.torfilx.core.model.MediaType
import com.torfilx.core.model.SourceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * It exists so the app is useful with no media server at all: the bundled titles are playable over
 * BitTorrent, while anything the server offers is merged in on top. Entries whose magnet links are
 * malformed are dropped at load time and logged — a broken magnet must never reach the player as a
 * mysterious failure.
 */
@Singleton
class BundledCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    @Volatile
    private var cached: List<CatalogItem>? = null

    /** Loaded once and kept: the asset is small and never changes at runtime. */
    fun items(): List<CatalogItem> = cached ?: load().also { cached = it }

    fun item(id: String): CatalogItem? = items().firstOrNull { it.item.id == id }

    fun sourcesFor(id: String): List<MediaSource> = item(id)?.sources.orEmpty()

    private fun load(): List<CatalogItem> = runCatching {
        val raw = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val entries = json.decodeFromString<List<CatalogEntryDto>>(raw)

        entries.mapIndexedNotNull { index, entry ->
            val title = entry.title.trim()
            if (title.isEmpty()) {
                TorfilxLog.w(TAG, "Catalogue entry $index has no title; skipped")
                return@mapIndexedNotNull null
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

            val id = "catalog-${title.slug()}-${entry.year.orEmpty()}"
            CatalogItem(
                item = MediaItem(
                    id = id,
                    type = MediaType.MOVIE,
                    title = title,
                    sortTitle = title.removePrefix("The ").trim(),
                    year = entry.year?.filter { it.isDigit() }?.toIntOrNull(),
                    runtimeMs = entry.runtimeMinutes?.let { it * 60_000L },
                    overview = entry.overview,
                    genres = entry.genres,
                    images = Images(poster = entry.imageUrl, backdrop = entry.imageUrl),
                    addedAtMs = null,
                    updatedAtMs = 0L,
                ),
                sources = sources,
            )
        }.also { TorfilxLog.i(TAG, "Bundled catalogue: ${it.size} titles") }
    }.getOrElse { error ->
        TorfilxLog.e(TAG, "Bundled catalogue could not be read", error)
        emptyList()
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
}
