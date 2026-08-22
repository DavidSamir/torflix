package com.torfilx.core.torrent

/**
 * Downsamples a piece bitfield into a fixed number of segments for drawing.
 *
 * A feature-length film is thousands of pieces; a strip on a television is a hundred-odd segments.
 * Handing the UI the raw bitfield would mean the flow, the state object and the composable all carry
 * thousands of booleans that get collapsed at draw time anyway — on a device where that allocation
 * happens on every status tick.
 *
 * Each segment is the **fraction** of its pieces that are present rather than a boolean, so a
 * partially-filled region reads as partially filled instead of rounding to full or empty. That
 * matters visually: streaming fills the front of a file first, and the interesting picture is the
 * gradient, not a binary edge.
 *
 * @param have one entry per piece of the file's range: true when that piece is on disk.
 * @param buckets segments to produce; clamped to at least one and never more than there are pieces.
 */
internal fun downsamplePieces(have: List<Boolean>, buckets: Int): List<Float> {
    if (have.isEmpty() || buckets <= 0) return emptyList()
    val target = buckets.coerceAtMost(have.size).coerceAtLeast(1)
    val result = ArrayList<Float>(target)
    for (bucket in 0 until target) {
        // Boundaries computed from the bucket index rather than by accumulating a step, so rounding
        // error cannot drift and leave the last bucket short or overrunning the array.
        val start = (bucket.toLong() * have.size / target).toInt()
        val end = ((bucket + 1).toLong() * have.size / target).toInt().coerceAtLeast(start + 1)
        var present = 0
        for (index in start until end.coerceAtMost(have.size)) {
            if (have[index]) present++
        }
        val width = (end.coerceAtMost(have.size) - start).coerceAtLeast(1)
        result += present.toFloat() / width
    }
    return result
}

/**
 * The piece index range covering a file inside a torrent.
 *
 * Only one file per torrent is ever downloaded, so the strip must describe *that file*, not the whole
 * torrent — otherwise a 4 GB torrent containing a 1.4 GB film would draw mostly empty forever.
 *
 * @return an inclusive range, clamped to the torrent's pieces.
 */
internal fun filePieceRange(
    fileOffset: Long,
    fileSizeBytes: Long,
    pieceLength: Int,
    numPieces: Int,
): IntRange {
    if (pieceLength <= 0 || numPieces <= 0 || fileSizeBytes <= 0) return IntRange.EMPTY
    val first = (fileOffset / pieceLength).toInt().coerceIn(0, numPieces - 1)
    val lastByte = fileOffset + fileSizeBytes - 1
    val last = (lastByte / pieceLength).toInt().coerceIn(first, numPieces - 1)
    return first..last
}
