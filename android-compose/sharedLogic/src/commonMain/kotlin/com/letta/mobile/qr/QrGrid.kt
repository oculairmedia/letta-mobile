package com.letta.mobile.qr

/**
 * Mutable module grid used while building one symbol (ISO/IEC 18004 sections 7.7-7.9): function
 * patterns, codeword placement, masking, and format/version information. Row-major, row then column.
 */
internal class QrGrid private constructor(
    val version: Int,
    val dark: BooleanArray,
    private val function: BooleanArray,
) {
    val size: Int = QrVersionTables.size(version)

    constructor(version: Int) : this(
        version,
        BooleanArray(QrVersionTables.size(version) * QrVersionTables.size(version)),
        BooleanArray(QrVersionTables.size(version) * QrVersionTables.size(version)),
    )

    fun copy(): QrGrid = QrGrid(version, dark.copyOf(), function.copyOf())

    fun isDark(row: Int, col: Int): Boolean = dark[row * size + col]

    private fun QrCell.index(): Int = row * size + col

    private fun inBounds(cell: QrCell): Boolean = cell.row in 0 until size && cell.col in 0 until size

    private fun setFunction(cell: QrCell, isDark: Boolean) {
        dark[cell.index()] = isDark
        function[cell.index()] = true
    }

    /** Timing, finder (with separators), alignment, reserved format area, dark module and version information. */
    fun drawFunctionPatterns(level: QrErrorCorrection) {
        drawTiming()
        drawFinder(QrCell(3, 3))
        drawFinder(QrCell(3, size - 4))
        drawFinder(QrCell(size - 4, 3))
        QrGridLayout.alignmentCenters(version).forEach { drawAlignment(it) }
        drawFormat(level, QrMask(0))
        drawVersion()
    }

    private fun drawTiming() {
        for (i in 0 until size) {
            setFunction(QrCell(6, i), i % 2 == 0)
            setFunction(QrCell(i, 6), i % 2 == 0)
        }
    }

    private fun drawFinder(center: QrCell) {
        for (offset in QrGridLayout.offsets(-4..4)) {
            val cell = center + offset
            if (inBounds(cell)) setFunction(cell, offset.ring != 2 && offset.ring != 4)
        }
    }

    private fun drawAlignment(center: QrCell) {
        for (offset in QrGridLayout.offsets(-2..2)) setFunction(center + offset, offset.ring != 1)
    }

    /** Write both copies of the format information for [level] and [mask], plus the always-dark module. */
    fun drawFormat(level: QrErrorCorrection, mask: QrMask) {
        val bits = QrBits(QrFormatInfo.formatBits(level, mask.index))
        paintBits(bits, QrGridLayout.formatCellsNearFinder())
        paintBits(bits, QrGridLayout.formatCellsSplit(size))
        setFunction(QrCell(size - 8, 8), true)
    }

    private fun drawVersion() {
        if (version < 7) return
        val bits = QrBits(QrFormatInfo.versionBits(version))
        val cells = QrGridLayout.versionCells(size)
        paintBits(bits, cells)
        paintBits(bits, cells.map { it.transposed() })
    }

    /** Make function modules of [cells], where cell i takes bit i of [bits]. */
    private fun paintBits(bits: QrBits, cells: List<QrCell>) {
        cells.forEachIndexed { i, cell -> setFunction(cell, bits[i]) }
    }

    /** Fill the non-function modules with the bits of [source] in the two-column zigzag, bottom-right first. */
    fun placeCodewords(source: QrBitSource) {
        for (cell in QrGridLayout.zigzag(size)) {
            if (source.hasNext() && !function[cell.index()]) dark[cell.index()] = source.next()
        }
    }

    /** XOR mask [mask] onto every non-function module; applying it twice restores the grid. */
    fun applyMask(mask: QrMask) {
        for (cell in QrGridLayout.allCells(size)) {
            if (!function[cell.index()] && mask.flips(cell)) dark[cell.index()] = !dark[cell.index()]
        }
    }
}
