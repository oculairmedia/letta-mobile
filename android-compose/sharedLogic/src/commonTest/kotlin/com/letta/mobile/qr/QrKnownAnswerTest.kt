package com.letta.mobile.qr

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Known-answer vectors from ISO/IEC 18004 and its worked examples. */
class QrKnownAnswerTest {

    // "HELLO WORLD" as alphanumeric, 1-M: mode 0010, count 11, six 11/6-bit groups, terminator, pad bytes.
    private val helloWorldOneM = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17)

    @Test
    fun helloWorldIsAlphanumericAndProducesTheWorkedExampleDataCodewords() {
        val data = QrData.of("HELLO WORLD")
        assertEquals(QrMode.ALPHANUMERIC, data.mode)
        assertContentEquals(helloWorldOneM, data.codewords(1, QrErrorCorrection.M))
    }

    @Test
    fun helloWorldAtLevelQIsTheSameStreamCutToThirteenCodewords() {
        assertContentEquals(helloWorldOneM.copyOf(13), QrData.of("HELLO WORLD").codewords(1, QrErrorCorrection.Q))
    }

    @Test
    fun reedSolomonRemainderMatchesTheWorkedExampleForOneM() {
        val ecc = QrReedSolomon.remainder(helloWorldOneM, QrReedSolomon.generator(10))
        assertContentEquals(intArrayOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23), ecc)
    }

    @Test
    fun singleBlockInterleavingIsDataThenEcc() {
        val all = QrReedSolomon.interleave(helloWorldOneM, 1, QrErrorCorrection.M)
        assertContentEquals(helloWorldOneM + intArrayOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23), all)
    }

    @Test
    fun galoisFieldMultiplicationWrapsWithTheQrPolynomial() {
        assertEquals(0x1D, QrReedSolomon.multiply(0x80, 0x02))
        assertEquals(1, QrReedSolomon.multiply(0x8E, 0x02))
        assertEquals(0, QrReedSolomon.multiply(0, 0xFF))
        assertEquals(0x57, QrReedSolomon.multiply(0x57, 1))
    }

    @Test
    fun formatInformationMatchesTheSpecificationTableForEveryLevelAndMask() {
        val expected = mapOf(
            QrErrorCorrection.L to listOf(
                "111011111000100", "111001011110011", "111110110101010", "111100010011101",
                "110011000101111", "110001100011000", "110110001000001", "110100101110110",
            ),
            QrErrorCorrection.M to listOf(
                "101010000010010", "101000100100101", "101111001111100", "101101101001011",
                "100010111111001", "100000011001110", "100111110010111", "100101010100000",
            ),
            QrErrorCorrection.Q to listOf(
                "011010101011111", "011000001101000", "011111100110001", "011101000000110",
                "010010010110100", "010000110000011", "010111011011010", "010101111101101",
            ),
            QrErrorCorrection.H to listOf(
                "001011010001001", "001001110111110", "001110011100111", "001100111010000",
                "000011101100010", "000001001010101", "000110100001100", "000100000111011",
            ),
        )
        for ((level, words) in expected) {
            words.forEachIndexed { mask, word ->
                assertEquals(word.toInt(2), QrFormatInfo.formatBits(level, mask), "format $level mask $mask")
            }
        }
    }

    @Test
    fun versionInformationMatchesTheSpecificationTable() {
        assertEquals("000111110010010100".toInt(2), QrFormatInfo.versionBits(7))
        assertEquals("001000010110111100".toInt(2), QrFormatInfo.versionBits(8))
        assertEquals("101000110001101001".toInt(2), QrFormatInfo.versionBits(40))
    }

    @Test
    fun alignmentCentresMatchTheSpecificationTable() {
        assertContentEquals(intArrayOf(), QrVersionTables.alignmentCenters(1))
        assertContentEquals(intArrayOf(6, 18), QrVersionTables.alignmentCenters(2))
        assertContentEquals(intArrayOf(6, 22, 38), QrVersionTables.alignmentCenters(7))
        assertContentEquals(intArrayOf(6, 34, 60, 86, 112, 138), QrVersionTables.alignmentCenters(32))
        assertContentEquals(intArrayOf(6, 24, 50, 76, 102, 128, 154), QrVersionTables.alignmentCenters(36))
        assertContentEquals(intArrayOf(6, 30, 58, 86, 114, 142, 170), QrVersionTables.alignmentCenters(40))
    }

    @Test
    fun dataCodewordCountsMatchTheSpecificationTable() {
        val v1 = QrErrorCorrection.entries.map { QrVersionTables.dataCodewords(1, it) }
        val v40 = QrErrorCorrection.entries.map { QrVersionTables.dataCodewords(40, it) }
        assertEquals(listOf(19, 16, 13, 9), v1)
        assertEquals(listOf(2956, 2334, 1666, 1276), v40)
        assertEquals(26, QrVersionTables.totalCodewords(1))
        assertEquals(3706, QrVersionTables.totalCodewords(40))
    }

    @Test
    fun nonAsciiTextIsUtf8BytesBehindAUtf8EciHeader() {
        val data = QrData.of("h\u00e9")
        assertEquals(QrMode.BYTE, data.mode)
        // 0111 00011010 (ECI 26) | 0100 (byte) | 00000011 (3 bytes) | 68 C3 A9 | terminator.
        val codewords = data.codewords(1, QrErrorCorrection.H)
        assertContentEquals(intArrayOf(0x71, 0xA4, 0x03, 0x68, 0xC3, 0xA9, 0x00, 0xEC, 0x11), codewords)
    }

    @Test
    fun asciiLowercaseTextIsPlainByteModeWithoutEci() {
        val codewords = QrData.of("ab").codewords(1, QrErrorCorrection.H)
        assertContentEquals(intArrayOf(0x40, 0x26, 0x16, 0x20, 0xEC, 0x11, 0xEC, 0x11, 0xEC), codewords)
    }
}
