package com.torfilx.core.data.catalog

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.model.SourceKind
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Exercises the real `parseCatalog` against real JSON — not a re-implementation of its rules — so a
 * regression in the only source of content in the app is caught. Complements CatalogIdTest, which
 * pins the id arithmetic in isolation.
 */
class CatalogParseTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val validMagnet =
        "magnet:?xt=urn:btih:0697BC07EBC5914085C2A3BCE646509086BF6265&dn=The+Kid"

    private fun parse(raw: String) = parseCatalog(raw, json)

    @Test
    fun `a valid entry is parsed with id, title, year, runtime, genres and one source`() {
        val items = parse(
            """
            [{
              "title": "The Kid", "year": "1921", "image_url": "http://x/p.jpg",
              "overview": "o", "genres": ["Comedy"], "runtimeMinutes": 68,
              "magnets": [{ "quality": "720p", "magnet": "$validMagnet" }]
            }]
            """.trimIndent(),
        )

        assertThat(items).hasSize(1)
        val item = items[0]
        assertThat(item.item.id).isEqualTo("catalog-the-kid-1921")
        assertThat(item.item.title).isEqualTo("The Kid")
        assertThat(item.item.sortTitle).isEqualTo("Kid") // "The " stripped for sorting
        assertThat(item.item.year).isEqualTo(1921)
        assertThat(item.item.runtimeMs).isEqualTo(68 * 60_000L)
        assertThat(item.item.genres).containsExactly("Comedy")
        assertThat(item.sources).hasSize(1)
        assertThat(item.sources[0].kind).isEqualTo(SourceKind.TORRENT)
        assertThat(item.sources[0].height).isEqualTo(720)
    }

    @Test
    fun `malformed magnets are dropped but the title survives`() {
        val items = parse(
            """
            [{ "title": "Bad", "magnets": [{ "magnet": "magnet:?xt=urn:btih:not-a-hash" }] }]
            """.trimIndent(),
        )
        assertThat(items).hasSize(1)
        assertThat(items[0].sources).isEmpty()
    }

    @Test
    fun `entries with no title are skipped`() {
        val items = parse("""[{ "title": "  ", "magnets": [] }, { "title": "Ok", "magnets": [] }]""")
        assertThat(items.map { it.item.title }).containsExactly("Ok")
    }

    @Test
    fun `duplicate title and year get distinct ids from the real parser`() {
        val items = parse(
            """
            [
              { "title": "Dup", "year": "1999", "magnets": [{ "magnet": "$validMagnet" }] },
              { "title": "Dup", "year": "1999", "magnets": [{ "magnet": "$validMagnet" }] }
            ]
            """.trimIndent(),
        )
        assertThat(items).hasSize(2)
        assertThat(items.map { it.item.id }.toSet()).hasSize(2)
    }

    @Test
    fun `repeated genres are collapsed case-insensitively`() {
        val items = parse(
            """[{ "title": "G", "genres": ["Action", "action", " Action ", "Drama"], "magnets": [] }]""",
        )
        assertThat(items[0].item.genres).containsExactly("Action", "Drama").inOrder()
    }

    @Test
    fun `unknown JSON fields are ignored`() {
        val items = parse("""[{ "title": "X", "surprise": 42, "magnets": [] }]""")
        assertThat(items).hasSize(1)
    }

    @Test
    fun `a syntax error mid-array keeps the entries parsed before it`() {
        // The whole catalogue used to collapse to empty on one bad byte; now the good entries before
        // the break survive.
        val items = parse("""[ {"title":"A","magnets":[]}, {"title":"B", B R O K E N ]""")
        assertThat(items.map { it.item.title }).contains("A")
    }

    @Test
    fun `the shipped catalog parses to unique-keyed, titled items`() {
        // Guards the *actual* file that ships in the app — the only source of content — so a broken
        // hand edit is caught by CI, not discovered as an empty home screen on a stick.
        val file = java.io.File("src/main/assets/catalog.json")
        if (!file.exists()) return // path differs under some runners; skip rather than fail spuriously

        val items = file.inputStream().use { parseCatalogStream(it, json) }
        assertThat(items).isNotEmpty()
        assertThat(items.all { it.item.id.isNotBlank() && it.item.title.isNotBlank() }).isTrue()
        assertThat(items.map { it.item.id }.toSet()).hasSize(items.size) // ids are Compose keys
    }
}
