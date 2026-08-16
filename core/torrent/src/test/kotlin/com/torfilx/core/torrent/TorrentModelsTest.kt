package com.torfilx.core.torrent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MagnetLinkTest {

    @Test
    fun `valid hex magnet yields a lowercase info hash`() {
        val magnet = "magnet:?xt=urn:btih:${HASH.uppercase()}&dn=The+Kid&tr=udp%3A%2F%2Ftracker"
        assertThat(MagnetLink.infoHashOf(magnet)).isEqualTo(HASH)
        assertThat(MagnetLink.isValid(magnet)).isTrue()
    }

    @Test
    fun `display name is url decoded`() {
        val magnet = "magnet:?xt=urn:btih:$HASH&dn=The+Kid+%281921%29"
        assertThat(MagnetLink.displayName(magnet)).isEqualTo("The Kid (1921)")
    }

    @Test
    fun `base32 info hashes are accepted`() {
        val base32 = "A2BCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val magnet = "magnet:?xt=urn:btih:${base32.take(32)}"
        assertThat(MagnetLink.isValid(magnet)).isTrue()
    }

    @Test
    fun `placeholder and malformed magnets are rejected rather than half-accepted`() {
        // This is the exact shape of a broken catalogue entry: the hash slot holds junk.
        val placeholder = "magnet:?xt=urn:btih:%29+%5B720p%5D+%=udp%3A%2Fannounce&tr=udp%3A%2F%2Ftracker"
        assertThat(MagnetLink.isValid(placeholder)).isFalse()

        assertThat(MagnetLink.isValid("")).isFalse()
        assertThat(MagnetLink.isValid("http://example.com/file.torrent")).isFalse()
        assertThat(MagnetLink.isValid("magnet:?dn=No+Hash")).isFalse()
        assertThat(MagnetLink.isValid("magnet:?xt=urn:btih:tooshort")).isFalse()
        assertThat(MagnetLink.isValid("magnet:?xt=urn:sha1:$HASH")).isFalse()
    }

    private companion object {
        const val HASH = "0697bc07ebc5914085c2a3bce646509086bf6265"
    }
}

class StorageBudgetTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `budget is a share of free space after the reserve`() {
        val budget = StorageBudget(fractionOfFree = 0.5f, maxBytes = 100 * gb, reserveBytes = 500L * 1024 * 1024)
        // 4 GB free, keep 500 MB back, use half of the rest.
        val cap = budget.capBytes(4 * gb)
        assertThat(cap).isEqualTo(((4 * gb - 500L * 1024 * 1024) * 0.5).toLong())
    }

    @Test
    fun `an almost-full device gets a zero budget instead of a negative one`() {
        val budget = StorageBudget()
        assertThat(budget.capBytes(100L * 1024 * 1024)).isEqualTo(0L)
        assertThat(budget.capBytes(0L)).isEqualTo(0L)
    }

    @Test
    fun `the absolute maximum wins on a large disk`() {
        val budget = StorageBudget(fractionOfFree = 0.5f, maxBytes = 2 * gb)
        assertThat(budget.capBytes(500 * gb)).isEqualTo(2 * gb)
    }

    @Test
    fun `default budget keeps half of free space for the user`() {
        val budget = StorageBudget.DEFAULT
        val free = 10 * gb
        assertThat(budget.capBytes(free)).isLessThan(free / 2 + 1)
    }
}
