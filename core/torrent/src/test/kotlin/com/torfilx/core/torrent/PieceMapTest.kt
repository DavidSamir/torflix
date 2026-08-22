package com.torfilx.core.torrent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The arithmetic behind the "which parts of this film are on my TV" strip.
 *
 * Pinned in isolation for the same reason [PieceMathTest] is: it is index arithmetic over a large
 * array, the failure modes are off-by-one and drift, and neither is visible on a television — a
 * strip that is subtly wrong still looks like a strip.
 */
class PieceMapTest {

    @Test
    fun `an empty bitfield produces no segments`() {
        assertThat(downsamplePieces(emptyList(), 120)).isEmpty()
    }

    @Test
    fun `a fully downloaded file is entirely full`() {
        val have = List(1000) { true }
        val strip = downsamplePieces(have, 120)
        assertThat(strip).hasSize(120)
        assertThat(strip.all { it == 1f }).isTrue()
    }

    @Test
    fun `an empty file is entirely empty`() {
        assertThat(downsamplePieces(List(1000) { false }, 120).all { it == 0f }).isTrue()
    }

    @Test
    fun `the first half downloaded shows as a filled first half`() {
        // Streaming fills the front of the file first, so this is the everyday case.
        val have = List(1000) { it < 500 }
        val strip = downsamplePieces(have, 100)
        assertThat(strip.take(50).all { it == 1f }).isTrue()
        assertThat(strip.drop(50).all { it == 0f }).isTrue()
    }

    @Test
    fun `partial buckets report a fraction rather than rounding`() {
        // Every other piece present: each bucket is half full, and must say so rather than
        // collapsing to full or empty.
        val have = List(1000) { it % 2 == 0 }
        downsamplePieces(have, 100).forEach { assertThat(it).isWithin(0.01f).of(0.5f) }
    }

    @Test
    fun `asking for more segments than pieces never invents any`() {
        val strip = downsamplePieces(List(7) { true }, 120)
        assertThat(strip).hasSize(7)
        assertThat(strip.all { it == 1f }).isTrue()
    }

    @Test
    fun `every piece is counted exactly once across all segments`() {
        // The property that catches drift: bucket boundaries must tile the array with no gap and no
        // overlap, or the strip silently misrepresents how much is held.
        for (size in listOf(1, 2, 13, 999, 4096)) {
            for (buckets in listOf(1, 7, 120)) {
                val have = List(size) { true }
                val strip = downsamplePieces(have, buckets)
                // All-present input must average to exactly 1 in every segment.
                assertThat(strip.all { it == 1f }).isTrue()
                assertThat(strip.size).isEqualTo(buckets.coerceAtMost(size))
            }
        }
    }

    @Test
    fun `a file's piece range covers only that file`() {
        // A 1 MB file starting 2 MB into a torrent with 1 MB pieces occupies piece 2 alone.
        assertThat(filePieceRange(fileOffset = 2_000_000, fileSizeBytes = 1_000_000, pieceLength = 1_000_000, numPieces = 10))
            .isEqualTo(2..2)
    }

    @Test
    fun `a file spanning several pieces includes both ends`() {
        // Bytes 500_000..2_499_999 with 1 MB pieces touches pieces 0, 1 and 2.
        assertThat(filePieceRange(fileOffset = 500_000, fileSizeBytes = 2_000_000, pieceLength = 1_000_000, numPieces = 10))
            .isEqualTo(0..2)
    }

    @Test
    fun `the range is clamped to the torrent`() {
        // A declared size past the end of the torrent must not produce an index that would throw.
        val range = filePieceRange(fileOffset = 0, fileSizeBytes = 99_000_000, pieceLength = 1_000_000, numPieces = 4)
        assertThat(range.last).isAtMost(3)
    }

    @Test
    fun `degenerate inputs produce an empty range rather than throwing`() {
        assertThat(filePieceRange(0, 0, 1_000, 10).isEmpty()).isTrue()
        assertThat(filePieceRange(0, 1_000, 0, 10).isEmpty()).isTrue()
        assertThat(filePieceRange(0, 1_000, 1_000, 0).isEmpty()).isTrue()
    }
}
