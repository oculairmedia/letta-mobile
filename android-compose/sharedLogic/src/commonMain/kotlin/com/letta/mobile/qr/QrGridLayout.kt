package com.letta.mobile.qr

/** Where the parts of a symbol sit in a grid of a given size (ISO/IEC 18004 sections 7.7-7.9), as ordered cell lists. */
internal object QrGridLayout {
    /** Alignment pattern centres: every pairing of the version's centre coordinates except the three finder corners. */
    fun alignmentCenters(version: Int): List<QrCell> {
        val centers = QrVersionTables.alignmentCenters(version)
        val last = centers.size - 1
        val finderCorners = setOf(0 to 0, 0 to last, last to 0)
        return centers.indices.flatMap { i -> centers.indices.map { j -> i to j } }
            .filter { it !in finderCorners }
            .map { (i, j) -> QrCell(centers[i], centers[j]) }
    }

    /** Every offset from the origin with both coordinates in [span]. */
    fun offsets(span: IntRange): List<QrCell> = span.flatMap { dr -> span.map { dc -> QrCell(dr, dc) } }

    /** Cells of format bits 0-14 around the top-left finder. */
    fun formatCellsNearFinder(): List<QrCell> =
        (0..5).map { QrCell(it, 8) } + listOf(QrCell(7, 8), QrCell(8, 8), QrCell(8, 7)) + (9 until 15).map { QrCell(8, 14 - it) }

    /** Cells of format bits 0-14 split between the top-right and bottom-left finders of a [size]-module grid. */
    fun formatCellsSplit(size: Int): List<QrCell> =
        (0 until 8).map { QrCell(8, size - 1 - it) } + (8 until 15).map { QrCell(size - 15 + it, 8) }

    /** Cells of version bits 0-17 in the block beside the top-right finder of a [size]-module grid. */
    fun versionCells(size: Int): List<QrCell> = (0 until 18).map { QrCell(it / 3, size - 11 + it % 3) }

    /** Every module of a [size]-module grid in codeword placement order: column pairs right to left, zigzag. */
    fun zigzag(size: Int): List<QrCell> {
        val rights = (size - 1 downTo 8 step 2).toList() + listOf(5, 3, 1)
        return rights.flatMap { right ->
            val upward = ((right + 1) and 2) == 0
            (0 until size).flatMap { step ->
                val row = if (upward) size - 1 - step else step
                listOf(QrCell(row, right), QrCell(row, right - 1))
            }
        }
    }

    /** Every module of a [size]-module grid, row-major. */
    fun allCells(size: Int): List<QrCell> = (0 until size).flatMap { row -> (0 until size).map { col -> QrCell(row, col) } }
}
