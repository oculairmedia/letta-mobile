package com.letta.mobile.qr

import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-gw0h1: smoke tests for the QR encoder + renderer. The encoder
 * is the only piece of the CLI that touches the protocol wire format with
 * the camera, so we round-trip the payload through ZXing's decoder to prove
 * the matrix is actually scannable, not merely "looks like a QR".
 *
 * Tests live in jvmTest (not commonTest) because they rely on ZXing's
 * javase BufferedImage helpers.
 */
class QrCodeTest {

    @Test
    fun encodeProducesScannableMatrix() {
        val content = "letta-qr-v1.eyJ2ZXJzaW9uIjoxLCJub2RlX2lkIjoiYWJjZDEyMzQiLCJzaWduZWRfc2VjcmV0IjoiaW52aXRlOmRlYWRiZWVmIn0"
        val matrix = QrCode.encode(content)
        // ZXing's `MultiFormatWriter` includes a 1-module quiet zone on each
        // side, so the matrix size is `data_modules + 2`. The data modules
        // for versions 1..10 range from 21..57, so the matrix is 23..59.
        assertTrue(matrix.size in 23..59, "matrix size must fit 23..59 modules, got ${matrix.size}")
        // The data-area finder pattern lives at [1,1]..[7,7] (top-left
        // corner of the data area, just past the quiet zone).
        val off = 1
        for (i in 0..6) {
            assertEquals(true, matrix[off + 0, off + i], "top row of TL finder must be dark at col $i")
            assertEquals(true, matrix[off + 6, off + i])
            assertEquals(true, matrix[off + i, off + 0])
            assertEquals(true, matrix[off + i, off + 6])
        }
        // Inner 3x3 square must be dark.
        for (r in 2..4) for (c in 2..4) assertEquals(true, matrix[off + r, off + c])
        // Round-trip: serialise through the PNG renderer, then decode the
        // PNG with ZXing's reader. This is the same path the CLI uses
        // (renderer writes PNG, scanner reads the image), so it's the
        // authoritative round-trip.
        val decoded = decodeMatrix(matrix)
        assertEquals(content, decoded, "encoder/decoder round-trip must produce identical content")
    }

    @Test
    fun encodeRejectsEmptyInput() {
        try {
            QrCode.encode("")
            error("expected IllegalArgumentException for empty content")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("empty") == true, e.message)
        }
    }

    @Test
    fun renderTextProducesNonEmptyUnicodeOutput() {
        val content = "letta-qr-v1.eyJ2ZXJzaW9uIjoxLCJub2RlX2lkIjoiYWJjZDEyMzQifQ"
        val matrix = QrCode.encode(content)
        val text = QrRenderer.renderText(matrix)
        assertTrue(text.isNotEmpty(), "text render must produce non-empty output")
        // Expect at least one half-block character from Unicode.
        assertTrue(
            text.contains('█') || text.contains('▀') || text.contains('▄'),
            "text output should contain at least one half-block character, got: $text",
        )
        // Line count is `matrix.size / 2 + 2 + (matrix.size % 2)` for the
        // data rows + 1 (top quiet zone) + 1 (bottom quiet zone). Our
        // renderer appends a trailing '\n' after the data + bottom quiet
        // zone, so the count above is +1 to that arithmetic. We assert
        // >= matrix.size / 2 (sanity) and the line count is non-zero.
        val minExpectedLines = matrix.size / 2
        val actualNewlines = text.count { it == '\n' }
        assertTrue(
            actualNewlines >= minExpectedLines,
            "expected at least $minExpectedLines newlines, got $actualNewlines (size ${matrix.size})",
        )
    }

    @Test
    fun renderPngProducesValidPngFile() {
        val content = "letta-qr-v1.eyJ2ZXJzaW9uIjoxLCJub2RlX2lkIjoiYWJjZDEyMzQifQ"
        val matrix = QrCode.encode(content)
        val tmp = java.io.File.createTempFile("qr-test-", ".png")
        tmp.deleteOnExit()
        try {
            val bytesWritten = QrRenderer.writePng(matrix, tmp)
            assertTrue(bytesWritten > 0, "PNG must have non-zero size, got $bytesWritten")
            // Magic bytes: PNG starts with 0x89 50 4E 47 0D 0A 1A 0A.
            val head = tmp.readBytes().take(8).toByteArray()
            val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            assertTrue(head.contentEquals(expected), "PNG header mismatch: ${head.toHex()}")
            // The PNG must decode back to the same matrix (round-trip).
            val image = ImageIO.read(tmp) ?: error("ImageIO.read returned null")
            assertEquals(BufferedImage.TYPE_BYTE_GRAY, image.type, "renderer must use TYPE_BYTE_GRAY")
            val decoded = decodeImage(image)
            assertEquals(content, decoded, "PNG round-trip must match the source content")
        } finally {
            tmp.delete()
        }
    }

    private fun decodeMatrix(matrix: QrCode.Matrix): String {
        // Round-trip via the PNG renderer (the same path the CLI uses for
        // `--qr-format png=`). The renderer adds a 4-module quiet zone, an
        // 8x scale, and a `TYPE_BYTE_GRAY` BufferedImage — exactly the
        // scanner-friendly layout ZXing expects.
        val tmp = java.io.File.createTempFile("qr-round-trip-", ".png")
        tmp.deleteOnExit()
        try {
            QrRenderer.writePng(matrix, tmp)
            val image = ImageIO.read(tmp) ?: error("ImageIO.read returned null")
            return decodeImage(image)
        } finally {
            tmp.delete()
        }
    }

    private fun decodeImage(img: BufferedImage): String {
        val source = BufferedImageLuminanceSource(img)
        val binarizer = HybridBinarizer(source)
        val bitmap = com.google.zxing.BinaryBitmap(binarizer)
        val reader = MultiFormatReader()
        val result = reader.decode(bitmap)
        return result.text
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
