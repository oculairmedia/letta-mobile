package com.letta.mobile.qr

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Function patterns, version choice, capacities and the too-long error of [QrCodeGenerator]. */
class QrSymbolStructureTest {

    private fun maxBytes(version: Int, level: QrErrorCorrection): Int =
        (QrData.capacityBits(version, level) - 4 - QrMode.BYTE.countBits(version)) / 8

    private fun encoded(text: String, level: QrErrorCorrection = QrErrorCorrection.M): QrMatrix =
        assertIs<QrEncodeResult.Encoded>(QrCodeGenerator.encode(text, level)).matrix

    // A fixed mask skips the eight-way penalty search where only the version matters.
    private fun quick(text: String, level: QrErrorCorrection): QrEncodeResult = QrCodeGenerator.encode(text, level, mask = 0)

    @Test
    fun byteCapacitiesMatchTheSpecificationTable() {
        val expected = mapOf(
            1 to listOf(17, 14, 11, 7),
            2 to listOf(32, 26, 20, 14),
            10 to listOf(271, 213, 151, 119),
            40 to listOf(2953, 2331, 1663, 1273),
        )
        for ((version, capacities) in expected) {
            assertEquals(capacities, QrErrorCorrection.entries.map { maxBytes(version, it) }, "version $version")
        }
    }

    @Test
    fun alphanumericCapacityBoundaryAtVersionOne() {
        val limits = mapOf(QrErrorCorrection.L to 25, QrErrorCorrection.M to 20, QrErrorCorrection.Q to 16, QrErrorCorrection.H to 10)
        for ((level, limit) in limits) {
            assertEquals(1, encoded("A".repeat(limit), level).version, "$level at $limit")
            assertEquals(2, encoded("A".repeat(limit + 1), level).version, "$level at ${limit + 1}")
        }
    }

    @Test
    fun everyVersionAndLevelHoldsExactlyItsByteCapacityAndOneMoreByteMovesUp() {
        for (level in QrErrorCorrection.entries) {
            for (version in 1..40) {
                val limit = maxBytes(version, level)
                val fits = assertIs<QrEncodeResult.Encoded>(quick("a".repeat(limit), level))
                assertEquals(version, fits.matrix.version, "$level v$version at $limit")
                val over = quick("a".repeat(limit + 1), level)
                if (version < 40) {
                    assertEquals(version + 1, assertIs<QrEncodeResult.Encoded>(over).matrix.version, "$level v$version over")
                } else {
                    assertIs<QrEncodeResult.ContentTooLong>(over)
                }
            }
        }
    }

    @Test
    fun contentPastVersionFortyIsATypedErrorWithSizesOnly() {
        val result = QrCodeGenerator.encode("a".repeat(2332), QrErrorCorrection.M)
        assertEquals(QrEncodeResult.ContentTooLong(requiredBits = 4 + 16 + 2332 * 8, capacityBits = 2334 * 8), result)
        val high = assertIs<QrEncodeResult.ContentTooLong>(QrCodeGenerator.encode("a".repeat(1274), QrErrorCorrection.H))
        assertEquals(1276 * 8, high.capacityBits)
    }

    @Test
    fun theEmptyStringIsAVersionOneSymbol() {
        assertEquals(1, encoded("").version)
    }

    @Test
    fun everyVersionHasTheSpecifiedFunctionPatterns() {
        for (version in 1..40) {
            val level = QrErrorCorrection.entries[version % 4]
            val matrix = encoded("a".repeat(maxBytes(version, level)), level)
            assertEquals(version, matrix.version)
            assertEquals(17 + 4 * version, matrix.size)
            assertFinders(matrix)
            assertTiming(matrix)
            assertTrue(matrix.isDark(matrix.size - 8, 8), "dark module v$version")
            assertAlignments(matrix)
            assertFormatCopies(matrix)
            if (version >= 7) assertVersionBlocks(matrix)
        }
    }

    @Test
    fun aPairingLinkOfAbout150CharactersFitsVersionEightAtLevelM() {
        val link = "meridian://join?t=letta-join-v1." + "eyJ2IjoxLCJuIjoiYWJjZGVmIn0".repeat(5).take(118)
        assertEquals(150, link.length)
        assertEquals(8, encoded(link).version)
    }

    @Test
    fun aFixedMaskIsAppliedAndRecordedInTheFormatInformation() {
        for (mask in 0..7) {
            val matrix = assertIs<QrEncodeResult.Encoded>(QrCodeGenerator.encode("HELLO WORLD", QrErrorCorrection.Q, mask)).matrix
            assertEquals(mask, matrix.mask)
            assertEquals(QrFormatInfo.formatBits(QrErrorCorrection.Q, mask), readFormatAroundTopLeft(matrix))
        }
    }

    @Test
    fun theChosenMaskHasTheLowestPenalty() {
        val text = "meridian://join?t=letta-join-v1.penalty"
        val chosen = encoded(text, QrErrorCorrection.L)
        val scores = (0..7).map { mask ->
            val matrix = assertIs<QrEncodeResult.Encoded>(QrCodeGenerator.encode(text, QrErrorCorrection.L, mask)).matrix
            QrMaskPenalty.score(gridOf(matrix))
        }
        assertEquals(scores.indexOf(scores.min()), chosen.mask)
    }

    @Test
    fun outOfRangeModulesAreRejected() {
        val matrix = encoded("x")
        assertFailsWith<IllegalArgumentException> { matrix.isDark(matrix.size, 0) }
        assertFailsWith<IllegalArgumentException> { matrix.isDark(0, -1) }
    }

    private fun gridOf(matrix: QrMatrix): QrGrid = QrGrid(matrix.version).also { grid ->
        for (row in 0 until matrix.size) for (col in 0 until matrix.size) grid.dark[row * matrix.size + col] = matrix.isDark(row, col)
    }

    private fun assertFinders(matrix: QrMatrix) {
        val last = matrix.size - 7
        for ((top, left) in listOf(0 to 0, 0 to last, last to 0)) assertFinderAt(matrix, top, left)
    }

    // The 7x7 finder plus its one-module light separator, clipped to the symbol.
    private fun assertFinderAt(matrix: QrMatrix, top: Int, left: Int) {
        for (r in -1..7) {
            for (c in -1..7) {
                val row = top + r
                val col = left + c
                if (row !in 0 until matrix.size || col !in 0 until matrix.size) continue
                val ring = maxOf(abs(r - 3), abs(c - 3))
                assertEquals(ring != 2 && ring != 4, matrix.isDark(row, col), "finder v${matrix.version} ($row,$col)")
            }
        }
    }

    private fun assertTiming(matrix: QrMatrix) {
        for (i in 8 until matrix.size - 8) {
            assertEquals(i % 2 == 0, matrix.isDark(6, i), "row timing v${matrix.version} col $i")
            assertEquals(i % 2 == 0, matrix.isDark(i, 6), "column timing v${matrix.version} row $i")
        }
    }

    private fun assertAlignments(matrix: QrMatrix) {
        val centers = QrVersionTables.alignmentCenters(matrix.version)
        for (i in centers.indices) {
            for (j in centers.indices) {
                if (!overlapsFinder(i, j, centers.lastIndex)) assertAlignmentAt(matrix, centers[i], centers[j])
            }
        }
    }

    private fun overlapsFinder(i: Int, j: Int, last: Int): Boolean = (i == 0 && (j == 0 || j == last)) || (i == last && j == 0)

    private fun assertAlignmentAt(matrix: QrMatrix, row: Int, col: Int) {
        for (dr in -2..2) {
            for (dc in -2..2) {
                val dark = maxOf(abs(dr), abs(dc)) != 1
                assertEquals(dark, matrix.isDark(row + dr, col + dc), "alignment v${matrix.version} at ($row,$col)")
            }
        }
    }

    private fun readFormatAroundTopLeft(matrix: QrMatrix): Int {
        val cells = (0..5).map { it to 8 } + listOf(7 to 8, 8 to 8, 8 to 7) + (9 until 15).map { 8 to 14 - it }
        return bitsOf(matrix, cells)
    }

    private fun assertFormatCopies(matrix: QrMatrix) {
        val size = matrix.size
        val expected = QrFormatInfo.formatBits(matrix.errorCorrection, matrix.mask)
        val second = (0 until 8).map { 8 to size - 1 - it } + (8 until 15).map { size - 15 + it to 8 }
        assertEquals(expected, readFormatAroundTopLeft(matrix), "first format copy v${matrix.version}")
        assertEquals(expected, bitsOf(matrix, second), "second format copy v${matrix.version}")
    }

    private fun assertVersionBlocks(matrix: QrMatrix) {
        val size = matrix.size
        val topRight = (0 until 18).map { it / 3 to size - 11 + it % 3 }
        val bottomLeft = (0 until 18).map { size - 11 + it % 3 to it / 3 }
        assertEquals(QrFormatInfo.versionBits(matrix.version), bitsOf(matrix, topRight), "version block v${matrix.version}")
        assertEquals(QrFormatInfo.versionBits(matrix.version), bitsOf(matrix, bottomLeft), "version block v${matrix.version}")
    }

    /** Bit i of the result is the module at cells[i]. */
    private fun bitsOf(matrix: QrMatrix, cells: List<Pair<Int, Int>>): Int =
        cells.foldIndexed(0) { i, acc, (row, col) -> if (matrix.isDark(row, col)) acc or (1 shl i) else acc }
}
