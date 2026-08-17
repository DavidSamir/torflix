package com.torfilx.core.torrent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The stream server must never read across a piece boundary into not-yet-downloaded data, which the
 * sparse file returns as zeros — corrupt bytes to the decoder. These guard the arithmetic that caps
 * every read to the current piece.
 */
class PieceMathTest {

    // A file starting 1000 bytes into the torrent, 256-byte pieces.
    private val fileOffset = 1000L
    private val pieceLength = 256

    @Test
    fun `piece index accounts for the file's offset within the torrent`() {
        // Absolute offset of file byte 0 is 1000 -> piece 3 (1000/256 = 3).
        assertThat(pieceIndexOf(fileOffset, pieceLength, numPieces = 100, fileByteOffset = 0)).isEqualTo(3)
        // File byte 24 -> absolute 1024 -> exactly piece 4.
        assertThat(pieceIndexOf(fileOffset, pieceLength, numPieces = 100, fileByteOffset = 24)).isEqualTo(4)
    }

    @Test
    fun `piece index is clamped to the last piece`() {
        assertThat(pieceIndexOf(fileOffset, pieceLength, numPieces = 4, fileByteOffset = 10_000)).isEqualTo(3)
    }

    @Test
    fun `bytes until piece end stops exactly at the boundary`() {
        // Absolute 1000 sits in piece 3 (768..1023); 24 bytes remain to the boundary at 1024.
        assertThat(bytesUntilPieceEnd(fileOffset, pieceLength, fileByteOffset = 0)).isEqualTo(24)
    }

    @Test
    fun `a read exactly on a boundary gets the whole next piece`() {
        // File byte 24 -> absolute 1024 -> start of piece 4; a full 256 bytes remain.
        assertThat(bytesUntilPieceEnd(fileOffset, pieceLength, fileByteOffset = 24)).isEqualTo(256)
    }

    @Test
    fun `bytes until piece end is never zero`() {
        // Any offset yields at least one byte so the read loop always makes progress.
        for (offset in 0L until 600L) {
            assertThat(bytesUntilPieceEnd(fileOffset, pieceLength, offset)).isAtLeast(1L)
        }
    }

    @Test
    fun `capping a chunk to the piece boundary never exceeds it`() {
        // Simulate the server's read cap for a 64 KB chunk across many positions.
        val chunk = 64L * 1024
        for (offset in 0L until 2048L) {
            val cap = bytesUntilPieceEnd(fileOffset, pieceLength, offset)
            val toRead = minOf(chunk, cap)
            val startPiece = pieceIndexOf(fileOffset, pieceLength, 100, offset)
            val endPiece = pieceIndexOf(fileOffset, pieceLength, 100, offset + toRead - 1)
            assertThat(endPiece).isEqualTo(startPiece)
        }
    }
}
