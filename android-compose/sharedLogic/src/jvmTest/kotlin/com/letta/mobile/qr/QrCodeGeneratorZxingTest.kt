package com.letta.mobile.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import java.awt.image.BufferedImage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cross-checks the common [QrCodeGenerator] against ZXing (already on this test classpath): every
 * symbol must decode back to its text, ASCII symbols must be module-for-module identical to ZXing's
 * own encoder under the same mask (same version, codewords, placement and format bits), and the
 * block tables, Reed-Solomon remainders and mask penalties must agree with ZXing's.
 */
class QrCodeGeneratorZxingTest {

    private val link = "meridian://join?t=letta-join-v1." +
        "eyJ2IjoxLCJub2RlIjoiN2Y0YzJhOWUxYjNkIiwic2NvcGUiOiJjaGF0IiwiZXhwIjoxNzYzMDAwMDAwfQ.c2lnbmF0dXJlLWJ5dGVz".repeat(2).take(118)

    private val payloads: List<Pair<String, QrErrorCorrection>> by lazy { generatePayloads() }

    @Test
    fun versionTablesAgreeWithZxingForEveryVersionAndLevel() {
        for (version in 1..40) {
            val zxing = Version.getVersionForNumber(version)
            assertEquals(zxing.totalCodewords, QrVersionTables.totalCodewords(version), "total v$version")
            assertContentEquals(zxing.alignmentPatternCenters, QrVersionTables.alignmentCenters(version), "alignment v$version")
            for (level in QrErrorCorrection.entries) {
                val blocks = zxing.getECBlocksForLevel(zxingLevel(level))
                assertEquals(blocks.ecCodewordsPerBlock, QrVersionTables.eccPerBlock(version, level), "ecc $level v$version")
                assertEquals(blocks.numBlocks, QrVersionTables.blockCount(version, level), "blocks $level v$version")
            }
        }
    }

    @Test
    fun reedSolomonRemaindersAgreeWithZxing() {
        val random = Random(18004)
        val zxing = ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256)
        repeat(200) {
            val eccLength = listOf(7, 10, 13, 15, 16, 17, 18, 20, 22, 24, 26, 28, 30).random(random)
            val data = IntArray(random.nextInt(1, 123)) { random.nextInt(256) }
            val padded = data.copyOf(data.size + eccLength).also { zxing.encode(it, eccLength) }
            val expected = padded.copyOfRange(data.size, padded.size)
            assertContentEquals(expected, QrReedSolomon.remainder(data, QrReedSolomon.generator(eccLength)))
        }
    }

    @Test
    fun everyPayloadDecodesBackToItsTextWithZxing() {
        for ((text, level) in payloads) {
            val matrix = encoded(text, level)
            val decoded = Decoder().decode(bitMatrix(matrix), utf8)
            assertEquals(text, decoded.text, "round trip at $level, ${text.length} chars")
            assertEquals(zxingLevel(level).toString(), decoded.ecLevel)
        }
    }

    @Test
    fun asciiPayloadsAreModuleForModuleIdenticalToZxingsEncoderUnderTheSameMask() {
        var compared = 0
        for ((text, level) in asciiPayloads()) {
            val theirs = Encoder.encode(text, zxingLevel(level))
            val ours = assertIs<QrEncodeResult.Encoded>(QrCodeGenerator.encode(text, level, theirs.maskPattern)).matrix
            assertEquals(theirs.version.versionNumber, ours.version, "version for ${text.length} chars at $level")
            for (row in 0 until ours.size) {
                for (col in 0 until ours.size) {
                    assertEquals(theirs.matrix.get(col, row).toInt() == 1, ours.isDark(row, col), "module ($row,$col) of v${ours.version}")
                }
            }
            compared++
        }
        assertTrue(compared >= 200, "compared $compared symbols")
    }

    /**
     * Rules 1, 2 and 4 score exactly as ZXing does. For rule 3 ZXing treats modules beyond the edge
     * as dark, while this encoder treats them as the light quiet zone the standard places there, so
     * it finds every pattern ZXing finds and possibly more at the edges.
     */
    @Test
    fun maskPenaltiesAgreeWithZxingExceptForEdgeFinderPatterns() {
        for ((text, level) in asciiPayloads().take(60)) {
            for (mask in 0..7) {
                val ours = gridOf(assertIs<QrEncodeResult.Encoded>(QrCodeGenerator.encode(text, level, mask)).matrix)
                val theirs = byteMatrix(ours)
                assertEquals(zxingPenalty(1, theirs), QrMaskPenalty.runs(ours), "rule 1, mask $mask")
                assertEquals(zxingPenalty(2, theirs), QrMaskPenalty.blocks(ours), "rule 2, mask $mask")
                assertTrue(QrMaskPenalty.finderLike(ours) >= zxingPenalty(3, theirs), "rule 3, mask $mask")
                assertEquals(zxingPenalty(4, theirs), QrMaskPenalty.balance(ours), "rule 4, mask $mask")
            }
        }
    }

    @Test
    fun theLongPairingLinkScansThroughZxingsFinderDetectorAtSeveralScales() {
        for (scale in listOf(2, 3, 4, 8)) {
            val result = QRCodeReader().decode(bitmapOf(render(encoded(link, QrErrorCorrection.M), scale)), utf8)
            assertEquals(link, result.text, "scale $scale")
        }
    }

    // The finder-pattern detector also misses some of ZXing's own synthetic symbols, so the sweep
    // reads rendered images through the pure-barcode path: image sampling, no pattern search.
    @Test
    fun renderedSymbolsScanFromImages() {
        val hints = utf8 + (DecodeHintType.PURE_BARCODE to true)
        for ((text, level) in payloads.filterIndexed { i, _ -> i % 5 == 0 }) {
            val result = QRCodeReader().decode(bitmapOf(render(encoded(text, level), scale = 3)), hints)
            assertEquals(text, result.text, "scanned ${text.length} chars at $level")
        }
    }

    @Test
    fun theLongPairingLinkIsAVersionEightLevelMSymbol() {
        assertEquals(150, link.length)
        assertEquals(8, encoded(link, QrErrorCorrection.M).version)
    }

    private fun encoded(text: String, level: QrErrorCorrection): QrMatrix =
        assertIs<QrEncodeResult.Encoded>(QrCodeGenerator.encode(text, level)).matrix

    private val utf8: Map<DecodeHintType, Any> = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")

    private fun asciiPayloads() = payloads.filter { (t, _) -> t.all { it.code < 128 } && !t.all(Char::isDigit) }

    private fun bitmapOf(image: BufferedImage) = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))

    private fun gridOf(matrix: QrMatrix): QrGrid = QrGrid(matrix.version).also { grid ->
        for (row in 0 until matrix.size) for (col in 0 until matrix.size) grid.dark[row * matrix.size + col] = matrix.isDark(row, col)
    }

    private fun byteMatrix(grid: QrGrid): ByteMatrix = ByteMatrix(grid.size, grid.size).apply {
        for (row in 0 until grid.size) for (col in 0 until grid.size) set(col, row, grid.isDark(row, col))
    }

    // ZXing's MaskUtil is package-private; its rules are read reflectively as the reference scores.
    private fun zxingPenalty(rule: Int, matrix: ByteMatrix): Int {
        val method = Class.forName("com.google.zxing.qrcode.encoder.MaskUtil")
            .getDeclaredMethod("applyMaskPenaltyRule$rule", ByteMatrix::class.java)
        method.isAccessible = true
        return method.invoke(null, matrix) as Int
    }

    private fun zxingLevel(level: QrErrorCorrection): ErrorCorrectionLevel = ErrorCorrectionLevel.valueOf(level.name)

    private fun bitMatrix(matrix: QrMatrix): BitMatrix = BitMatrix(matrix.size).apply {
        for (row in 0 until matrix.size) for (col in 0 until matrix.size) if (matrix.isDark(row, col)) set(col, row)
    }

    private fun render(matrix: QrMatrix, scale: Int): BufferedImage {
        val side = (matrix.size + 8) * scale
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val row = y / scale - 4
                val col = x / scale - 4
                val dark = row in 0 until matrix.size && col in 0 until matrix.size && matrix.isDark(row, col)
                image.setRGB(x, y, if (dark) 0x000000 else 0xFFFFFF)
            }
        }
        return image
    }

    private fun generatePayloads(): List<Pair<String, QrErrorCorrection>> {
        val random = Random(2026)
        val urlChars = "abcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=%"
        val alnumChars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
        val base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_."
        val unicode = listOf("\u00e9", "\u00fc", "\u6f22", "\u5b57", "\u0436", "\uD83D\uDE00", "a", "b", " ")
        val out = ArrayList<Pair<String, QrErrorCorrection>>()
        val levels = QrErrorCorrection.entries
        for (i in 0 until 300) {
            val level = levels[i % 4]
            val length = if (i % 7 == 0) random.nextInt(200, 700) else random.nextInt(1, 200)
            val text = when (i % 5) {
                0 -> (1..length).map { urlChars.random(random) }.joinToString("")
                1 -> (1..length).map { alnumChars.random(random) }.joinToString("")
                2 -> "meridian://join?t=letta-join-v1." + (1..length).map { base64Chars.random(random) }.joinToString("")
                3 -> (1..length / 3 + 1).joinToString("") { unicode.random(random) }
                else -> (1..length).map { Char(random.nextInt(32, 127)) }.joinToString("")
            }
            out += text to level
        }
        out += link to QrErrorCorrection.M
        out += "HELLO WORLD" to QrErrorCorrection.Q
        return out
    }
}
