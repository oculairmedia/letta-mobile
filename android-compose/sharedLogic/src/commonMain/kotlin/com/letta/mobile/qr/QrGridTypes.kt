package com.letta.mobile.qr

import kotlin.jvm.JvmInline
import kotlin.math.abs

/** A module position in a [QrGrid]: [row] then [col], both zero-based from the top-left corner. */
internal data class QrCell(val row: Int, val col: Int) {
    /** Chebyshev distance from the origin: the ring number when this cell is an offset from a pattern centre. */
    val ring: Int get() = maxOf(abs(row), abs(col))

    operator fun plus(offset: QrCell): QrCell = QrCell(row + offset.row, col + offset.col)

    fun transposed(): QrCell = QrCell(col, row)
}

/** A bit field (format or version information) read least-significant bit first. */
@JvmInline
internal value class QrBits(private val value: Int) {
    operator fun get(index: Int): Boolean = ((value ushr index) and 1) != 0
}

/** Data mask pattern 0-7 (ISO/IEC 18004 Table 10). */
@JvmInline
internal value class QrMask(val index: Int) {
    /** Whether this mask inverts the module at [cell]. */
    fun flips(cell: QrCell): Boolean {
        val (row, col) = cell
        return when (index) {
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

/** Codeword bits, most-significant bit of each codeword first, consumed one at a time. */
internal class QrBitSource(private val codewords: IntArray) {
    private var next = 0

    fun hasNext(): Boolean = next < codewords.size * 8

    fun next(): Boolean {
        val bit = (codewords[next ushr 3] ushr (7 - (next and 7))) and 1
        next++
        return bit != 0
    }
}
