package com.letta.mobile.qr

/**
 * Per-version structure of a QR symbol (ISO/IEC 18004, Tables 1 and 9): how many modules carry
 * data, how the codewords split into Reed-Solomon blocks, and where alignment patterns sit.
 */
internal object QrVersionTables {
    const val MIN_VERSION = 1
    const val MAX_VERSION = 40

    // Error-correction codewords per block, indexed by [QrErrorCorrection.ordinal] then version (index 0 unused).
    private val ECC_CODEWORDS_PER_BLOCK: Array<IntArray> = arrayOf(
        intArrayOf(
            -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28,
            28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
        ),
        intArrayOf(
            -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
            26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
        ),
        intArrayOf(
            -1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30,
            28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
        ),
        intArrayOf(
            -1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28,
            30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
        ),
    )

    // Number of error-correction blocks, indexed like [ECC_CODEWORDS_PER_BLOCK].
    private val ERROR_CORRECTION_BLOCKS: Array<IntArray> = arrayOf(
        intArrayOf(
            -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8,
            8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25,
        ),
        intArrayOf(
            -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
            17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
        ),
        intArrayOf(
            -1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20,
            23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68,
        ),
        intArrayOf(
            -1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25,
            25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81,
        ),
    )

    /** Side length in modules of a symbol of [version]. */
    fun size(version: Int): Int = 17 + 4 * version

    /** Error-correction codewords in each block of [version] at [level]. */
    fun eccPerBlock(version: Int, level: QrErrorCorrection): Int = ECC_CODEWORDS_PER_BLOCK[level.ordinal][version]

    /** Number of Reed-Solomon blocks of [version] at [level]. */
    fun blockCount(version: Int, level: QrErrorCorrection): Int = ERROR_CORRECTION_BLOCKS[level.ordinal][version]

    /** Modules left for codewords (data plus error correction plus remainder bits) once function patterns are placed. */
    fun rawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val alignments = version / 7 + 2
            result -= (25 * alignments - 10) * alignments - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    /** All codewords (data plus error correction) of [version]. */
    fun totalCodewords(version: Int): Int = rawDataModules(version) / 8

    /** Data codewords available in [version] at [level]. */
    fun dataCodewords(version: Int, level: QrErrorCorrection): Int =
        totalCodewords(version) - eccPerBlock(version, level) * blockCount(version, level)

    /** Row/column coordinates of alignment pattern centres for [version]; empty for version 1. */
    fun alignmentCenters(version: Int): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
        val last = size(version) - 7
        return IntArray(count) { i -> if (i == 0) 6 else last - (count - 1 - i) * step }
    }
}
