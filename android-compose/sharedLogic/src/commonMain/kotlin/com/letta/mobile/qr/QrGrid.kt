package com.letta.mobile.qr

import kotlin.math.abs

/**
 * Mutable module grid used while building one symbol (ISO/IEC 18004 sections 7.7-7.9): function
 * patterns, codeword placement, masking, and format/version information. Row-major, row then column.
 */
internal class QrGrid private constructor(
    val version: Int,
    val size: Int,
    val dark: BooleanArray,
    private val function: BooleanArray,
) {
    constructor(version: Int) : this(
        version,
        QrVersionTables.size(version),
        BooleanArray(QrVersionTables.size(version) * QrVersionTables.size(version)),
        BooleanArray(QrVersionTables.size(version) * QrVersionTables.size(version)),
    )

    fun copy(): QrGrid = QrGrid(version, size, dark.copyOf(), function.copyOf())

    fun isDark(row: Int, col: Int): Boolean = dark[row * size + col]

    fun isFunction(row: Int, col: Int): Boolean = function[row * size + col]

    private fun setFunction(row: Int, col: Int, isDark: Boolean) {
        dark[row * size + col] = isDark
        function[row * size + col] = true
    }

    /** Timing, finder (with separators), alignment, reserved format area, dark module and version information. */
    fun drawFunctionPatterns(level: QrErrorCorrection) {
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(3, size - 4)
        drawFinder(size - 4, 3)
        drawAlignments()
        drawFormat(level, mask = 0)
        drawVersion()
    }

    private fun drawFinder(centerRow: Int, centerCol: Int) {
        for (dr in -4..4) {
            for (dc in -4..4) {
                val row = centerRow + dr
                val col = centerCol + dc
                val ring = maxOf(abs(dr), abs(dc))
                if (row in 0 until size && col in 0 until size) setFunction(row, col, ring != 2 && ring != 4)
            }
        }
    }

    private fun drawAlignments() {
        val centers = QrVersionTables.alignmentCenters(version)
        val last = centers.size - 1
        for (i in centers.indices) {
            for (j in centers.indices) {
                val overlapsFinder = (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)
                if (!overlapsFinder) drawAlignment(centers[i], centers[j])
            }
        }
    }

    private fun drawAlignment(centerRow: Int, centerCol: Int) {
        for (dr in -2..2) {
            for (dc in -2..2) setFunction(centerRow + dr, centerCol + dc, maxOf(abs(dr), abs(dc)) != 1)
        }
    }

    /** Write both copies of the format information for [level] and [mask], plus the always-dark module. */
    fun drawFormat(level: QrErrorCorrection, mask: Int) {
        val bits = QrFormatInfo.formatBits(level, mask)
        for (i in 0..5) setFunction(i, 8, bit(bits, i))
        setFunction(7, 8, bit(bits, 6))
        setFunction(8, 8, bit(bits, 7))
        setFunction(8, 7, bit(bits, 8))
        for (i in 9 until 15) setFunction(8, 14 - i, bit(bits, i))
        for (i in 0 until 8) setFunction(8, size - 1 - i, bit(bits, i))
        for (i in 8 until 15) setFunction(size - 15 + i, 8, bit(bits, i))
        setFunction(size - 8, 8, true)
    }

    private fun drawVersion() {
        if (version < 7) return
        val bits = QrFormatInfo.versionBits(version)
        for (i in 0 until 18) {
            val a = size - 11 + i % 3
            val b = i / 3
            setFunction(b, a, bit(bits, i))
            setFunction(a, b, bit(bits, i))
        }
    }

    /** Fill the non-function modules with [codewords] in the two-column zigzag, bottom-right first. */
    fun placeCodewords(codewords: IntArray) {
        var index = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            index = placeColumnPair(codewords, right, index)
            right -= 2
        }
    }

    /** Place bits from [start] into the column pair ending at [right]; returns the next bit index. */
    private fun placeColumnPair(codewords: IntArray, right: Int, start: Int): Int {
        var index = start
        val upward = ((right + 1) and 2) == 0
        for (step in 0 until size) {
            val row = if (upward) size - 1 - step else step
            for (col in right downTo right - 1) {
                if (isFunction(row, col) || index >= codewords.size * 8) continue
                dark[row * size + col] = bit(codewords[index ushr 3], 7 - (index and 7))
                index++
            }
        }
        return index
    }

    /** XOR mask [mask] (0-7) onto every non-function module; applying it twice restores the grid. */
    fun applyMask(mask: Int) {
        for (row in 0 until size) {
            for (col in 0 until size) {
                if (!isFunction(row, col) && maskBit(mask, row, col)) dark[row * size + col] = !dark[row * size + col]
            }
        }
    }

    private fun bit(value: Int, index: Int): Boolean = ((value ushr index) and 1) != 0

    companion object {
        /** Whether data mask [mask] inverts the module at [row], [col] (ISO/IEC 18004 Table 10). */
        fun maskBit(mask: Int, row: Int, col: Int): Boolean = when (mask) {
            0 -> (row + col) % 2 == 0
            1 -> row % 2 == 0
            2 -> col % 3 == 0
            3 -> (row + col) % 3 == 0
            4 -> (row / 2 + col / 3) % 2 == 0
            5 -> row * col % 2 + row * col % 3 == 0
            6 -> (row * col % 2 + row * col % 3) % 2 == 0
            7 -> ((row + col) % 2 + row * col % 3) % 2 == 0
            else -> throw IllegalArgumentException("mask must be 0-7")
        }
    }
}
