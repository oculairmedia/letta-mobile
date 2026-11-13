package com.letta.mobile.qr

import kotlin.math.abs

/**
 * Mask penalty scoring of ISO/IEC 18004 section 7.8.3: the mask with the lowest total wins.
 * For the finder-like rule, modules beyond the edge count as light: the quiet zone the standard puts there.
 */
internal object QrMaskPenalty {
    private const val RUN_PENALTY = 3
    private const val BLOCK_PENALTY = 3
    private const val FINDER_PENALTY = 40
    private const val BALANCE_PENALTY = 10
    private val FINDER_CORE = booleanArrayOf(true, false, true, true, true, false, true)

    /** Total penalty of [grid]. */
    fun score(grid: QrGrid): Int = runs(grid) + blocks(grid) + finderLike(grid) + balance(grid)

    /** Rule 1: each row or column run of five or more same-colour modules scores 3 plus one per extra module. */
    fun runs(grid: QrGrid): Int {
        var total = 0
        for (line in 0 until grid.size) total += lineRuns(grid, line, horizontal = true) + lineRuns(grid, line, horizontal = false)
        return total
    }

    private fun lineRuns(grid: QrGrid, line: Int, horizontal: Boolean): Int {
        var total = 0
        var run = 0
        var previous: Boolean? = null
        for (i in 0 until grid.size) {
            val current = at(grid, line, i, horizontal)
            run = if (current == previous) run + 1 else 1
            previous = current
            if (run == 5) total += RUN_PENALTY else if (run > 5) total += 1
        }
        return total
    }

    /** Rule 2: each 2x2 block of one colour scores 3 (overlapping blocks all count). */
    fun blocks(grid: QrGrid): Int {
        var total = 0
        for (row in 0 until grid.size - 1) {
            for (col in 0 until grid.size - 1) {
                val colour = grid.isDark(row, col)
                val same = grid.isDark(row, col + 1) == colour && grid.isDark(row + 1, col) == colour &&
                    grid.isDark(row + 1, col + 1) == colour
                if (same) total += BLOCK_PENALTY
            }
        }
        return total
    }

    /** Rule 3: each dark-light-dark-dark-dark-light-dark pattern with four light modules on either side scores 40. */
    fun finderLike(grid: QrGrid): Int {
        var count = 0
        for (line in 0 until grid.size) {
            for (start in 0..grid.size - FINDER_CORE.size) {
                if (finderAt(grid, line, start, horizontal = true)) count++
                if (finderAt(grid, line, start, horizontal = false)) count++
            }
        }
        return count * FINDER_PENALTY
    }

    private fun finderAt(grid: QrGrid, line: Int, start: Int, horizontal: Boolean): Boolean {
        val core = FINDER_CORE.indices.all { at(grid, line, start + it, horizontal) == FINDER_CORE[it] }
        return core && (lightSpan(grid, line, start - 4 until start, horizontal) ||
            lightSpan(grid, line, start + 7 until start + 11, horizontal))
    }

    private fun lightSpan(grid: QrGrid, line: Int, span: IntRange, horizontal: Boolean): Boolean =
        span.all { it !in 0 until grid.size || !at(grid, line, it, horizontal) }

    /** Rule 4: 10 points for each full 5% step the dark share deviates from 50%. */
    fun balance(grid: QrGrid): Int {
        val total = grid.size * grid.size
        val dark = grid.dark.count { it }
        return abs(dark * 2 - total) * 10 / total * BALANCE_PENALTY
    }

    private fun at(grid: QrGrid, line: Int, i: Int, horizontal: Boolean): Boolean =
        if (horizontal) grid.isDark(line, i) else grid.isDark(i, line)
}
