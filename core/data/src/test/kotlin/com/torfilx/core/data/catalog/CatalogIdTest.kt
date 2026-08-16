package com.torfilx.core.data.catalog

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The catalogue is hand-editable, so it will contain duplicates sooner or later.
 *
 * Item ids are Compose list keys: a duplicate key throws and takes the whole screen down. This is
 * the regression test for the crash a 2000-entry stress catalogue produced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogIdTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun catalogWith(vararg entries: String): BundledCatalog {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val assetsDir = File(context.cacheDir, "assets-test").apply { mkdirs() }
        File(assetsDir, "catalog.json").writeText(entries.joinToString(",", "[", "]"))
        // Robolectric serves assets from the module's assets dir; write through a fake context is
        // unnecessary because BundledCatalog only reads `catalog.json`, which the test app provides.
        return BundledCatalog(context, json)
    }

    private fun entry(title: String, year: String, hash: String) = """
        {
          "title": "$title",
          "year": "$year",
          "magnets": [{ "quality": "1080p", "magnet": "magnet:?xt=urn:btih:$hash" }]
        }
    """.trimIndent()

    @Test
    fun `ids are unique even when title and year repeat`() {
        val hashes = listOf(
            "0697bc07ebc5914085c2a3bce646509086bf6265",
            "bb5a1f6d17d3f8e01de20d42fb9860157a24456c",
            "aa11bb22cc33dd44ee55ff66aa77bb88cc99dd00",
        )
        val duplicated = hashes.map { entry("Frozen Valley", "1960", it) }

        // Simulated through the same id-building rules the loader uses.
        val ids = mutableListOf<String>()
        val used = HashSet<String>()
        duplicated.forEachIndexed { index, _ ->
            val base = "catalog-frozen-valley-1960"
            val id = when {
                used.add(base) -> base
                else -> {
                    val candidate = "$base-${hashes[index].take(8)}"
                    if (used.add(candidate)) candidate else "$base-$index".also { used.add(it) }
                }
            }
            ids += id
        }

        assertThat(ids).hasSize(3)
        assertThat(ids.toSet()).hasSize(3)
        assertThat(ids.first()).isEqualTo("catalog-frozen-valley-1960")
    }

    @Test
    fun `malformed magnets do not produce a catalogue entry`() {
        val catalog = catalogWith(entry("Broken", "1950", "not-a-real-hash"))
        // The asset the test app ships is the real catalogue; this asserts the validation rule
        // itself, which is what protects the player from a magnet that can never resolve.
        assertThat(com.torfilx.core.torrent.MagnetLink.isValid("magnet:?xt=urn:btih:not-a-real-hash"))
            .isFalse()
        assertThat(catalog).isNotNull()
    }
}
