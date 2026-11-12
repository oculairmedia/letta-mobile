package com.letta.mobile.qr

/**
 * Reed-Solomon error correction over GF(256) with the QR field polynomial
 * x^8 + x^4 + x^3 + x^2 + 1, and the block split and interleaving of ISO/IEC 18004 section 7.6.
 */
internal object QrReedSolomon {
    private const val FIELD_POLYNOMIAL = 0x11D

    /** Product of [x] and [y] in GF(256). */
    fun multiply(x: Int, y: Int): Int {
        var result = 0
        for (bit in 7 downTo 0) {
            result = (result shl 1) xor ((result ushr 7) * FIELD_POLYNOMIAL)
            result = result xor (((y ushr bit) and 1) * x)
        }
        return result
    }

    /**
     * Coefficients of the generator polynomial (x - a^0)(x - a^1)...(x - a^(degree-1)), highest
     * power first with the leading 1 omitted.
     */
    fun generator(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                result[j] = multiply(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = multiply(root, 0x02)
        }
        return result
    }

    /** The error-correction codewords for [data]: the remainder of data(x) * x^n divided by [generator]. */
    fun remainder(data: IntArray, generator: IntArray): IntArray {
        val result = IntArray(generator.size)
        for (byte in data) {
            val factor = byte xor result[0]
            result.copyInto(result, destinationOffset = 0, startIndex = 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor multiply(generator[i], factor)
        }
        return result
    }

    /** Split [data] into the blocks of [version] at [level], append each block's ECC, and interleave all codewords. */
    fun interleave(data: IntArray, version: Int, level: QrErrorCorrection): IntArray {
        val blocks = QrVersionTables.blockCount(version, level)
        val eccLength = QrVersionTables.eccPerBlock(version, level)
        val total = QrVersionTables.totalCodewords(version)
        val shortBlocks = blocks - total % blocks
        val shortDataLength = total / blocks - eccLength
        val generator = generator(eccLength)
        val dataBlocks = ArrayList<IntArray>(blocks)
        var offset = 0
        for (i in 0 until blocks) {
            val length = shortDataLength + if (i < shortBlocks) 0 else 1
            dataBlocks += data.copyOfRange(offset, offset + length)
            offset += length
        }
        val eccBlocks = dataBlocks.map { remainder(it, generator) }
        return interleaveColumns(dataBlocks, shortDataLength + 1) + interleaveColumns(eccBlocks, eccLength)
    }

    private fun interleaveColumns(blocks: List<IntArray>, longest: Int): IntArray {
        val out = ArrayList<Int>(blocks.size * longest)
        for (column in 0 until longest) {
            for (block in blocks) if (column < block.size) out += block[column]
        }
        return out.toIntArray()
    }
}
