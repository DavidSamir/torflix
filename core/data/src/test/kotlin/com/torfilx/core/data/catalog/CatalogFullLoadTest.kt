package com.torfilx.core.data.catalog

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Loads the **real shipped catalogue**, not a fixture.
 *
 * The reported symptom was "I don't see all the movies, I can see about 25". Ruling the data in or
 * out was the first thing that had to happen, and a three-entry fixture cannot do that: the parser
 * is a streaming one that keeps whatever it decoded before an error and silently stops, so a single
 * malformed entry half way down the file would truncate the library in exactly the way described
 * while every fixture-based test still passed.
 *
 * This asserts on the actual asset the APK ships, so a bad edit to `catalog.json` fails the build
 * rather than quietly shrinking the app's entire content.
 */
class CatalogFullLoadTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    /** The asset as it sits in the module, so the test reads the same bytes the app packages. */
    private val catalogFile = File("src/main/assets/catalog.json")

    @Test
    fun `the shipped catalogue file exists and is substantial`() {
        assertThat(catalogFile.exists()).isTrue()
        assertThat(catalogFile.length()).isGreaterThan(100_000L)
    }

    @Test
    fun `every entry in the shipped catalogue is parsed`() {
        val raw = catalogFile.readText()
        // Counting the top-level "title" keys is independent of the parser under test, so a parser
        // that stops early cannot make this assertion agree with it.
        val declared = Regex("\"title\"\\s*:").findAll(raw).count()
        val parsed = parseCatalog(raw, json)

        assertThat(declared).isGreaterThan(1_000)
        assertThat(parsed).hasSize(declared)
    }

    @Test
    fun `ids are unique across the whole catalogue`() {
        // Duplicate ids are Compose list keys: a collision is a hard crash in the grid, and with
        // 2000 hand-assembled entries the disambiguation path is not a corner case.
        val items = parseCatalog(catalogFile.readText(), json)
        val ids = items.map { it.item.id }
        assertThat(ids.toSet()).hasSize(ids.size)
    }

    @Test
    fun `the catalogue holds the full two thousand titles`() {
        // The number the app is supposed to ship. Pinned so that a truncated or half-regenerated
        // catalogue fails here rather than on a television.
        assertThat(parseCatalog(catalogFile.readText(), json).size).isAtLeast(2_000)
    }

    @Test
    fun `only the nine known bad entries lack a playable source`() {
        val items = parseCatalog(catalogFile.readText(), json)
        val unplayable = items.filter { it.sources.isEmpty() }.map { it.item.title }

        // All 2000 titles are in the catalogue and in the APK; nine of them carry magnets the
        // validator cannot accept, for three distinct reasons in the source data:
        //
        //  - "Megamind", "Walking with Dinosaurs 3D": no magnets at all.
        //  - "Baby Driver": magnets are unsubstituted scraper templates, "{$tt.info_hash}".
        //  - The remaining six: every magnet has a **41-character** info hash — a valid 40-hex hash
        //    with a spurious "1" prepended. All 17 of their magnets start with "1", so it is a
        //    systematic prefix from whatever generated the file, not random corruption. The correct
        //    hash cannot be recovered by guessing which character to drop, so these are left
        //    failing validation loudly rather than being "repaired" into a hash that would resolve
        //    to nothing and cost the viewer a two-minute timeout.
        //
        // Pinned exactly: if this number rises, magnet validation has started rejecting links it
        // used to accept, and the catalogue is quietly shrinking again.
        assertThat(unplayable).hasSize(9)
    }

    @Test
    fun `the catalogue exposes enough genres to fill the home rows`() {
        // Home builds one row per genre; if this collapses, Home looks empty for reasons that have
        // nothing to do with the number of titles.
        val items = parseCatalog(catalogFile.readText(), json)
        val genres = items.flatMap { it.item.genres }.distinct()
        assertThat(genres.size).isAtLeast(5)
    }

    @Test
    fun `parsing the whole catalogue is fast enough for a stick`() {
        // The catalogue is parsed on a background thread at launch, but it also blocks the first
        // screen that asks for it. On a 2016 Fire TV Stick this budget is roughly 10x what the
        // desktop JVM takes, which is the headroom this guards.
        val raw = catalogFile.readText()
        val startedAt = System.nanoTime()
        parseCatalog(raw, json)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertThat(elapsedMs).isLessThan(3_000)
    }
}
