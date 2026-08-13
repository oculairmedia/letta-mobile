package com.letta.mobile.qr

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import javax.imageio.ImageIO

/**
 * letta-mobile-gw0h1: render a QR matrix to (a) Unicode-block text for TTYs
 * or (b) a PNG file for headless install.
 *
 * Both renderers share the same scale model: one dark module maps to a
 * fixed number of pixels (PNG) or one of two block characters (text). The
 * text renderer uses Unicode half-block `▀` / `▄` so two QR rows fit in
 * one terminal line, keeping the on-screen QR roughly square. A quiet
 * zone of one module is added on each side (ZXing already adds the
 * margin; we mirror it in the text output for symmetry).
 *
 * The PNG renderer writes an 8-bit grayscale image — black for dark
 * modules, white for light, no anti-aliasing (sharp scanners prefer
 * pixel-aligned edges). Output is verified with the `file` command
 * (`PNG image data, WxH, 8-bit grayscale`).
 */
object QrRenderer {

    /**
     * Render [matrix] to Unicode half-block text. Each output row represents
     * two QR rows (top half + bottom half) so the on-screen aspect ratio
     * stays close to 1:1 even in monospace terminals with non-square cells.
     *
     * The output is a single string with '\n' line terminators and a leading
     * + trailing blank line for the quiet zone.
     */
    fun renderText(matrix: QrCode.Matrix): String {
        val size = matrix.size
        if (size == 0) return ""
        val sb = StringBuilder((size + 2) * (size / 2 + 1))
        // Top quiet zone row.
        sb.append(quietZoneLine(size))
        var r = 0
        while (r < size) {
            val topRow = r
            val bottomRow = r + 1
            sb.append(QUIET)
            for (c in 0 until size) {
                val top = matrix[topRow, c]
                val bottom = if (bottomRow < size) matrix[bottomRow, c] else false
                sb.append(halfBlockChar(top, bottom))
            }
            sb.append(QUIET)
            if (bottomRow < size) sb.append('\n')
            r += 2
        }
        sb.append('\n').append(quietZoneLine(size))
        return sb.toString()
    }

    /**
     * Render [matrix] to a PNG byte array. [scale] is the per-module pixel
     * size; 8 is the QR-Code-spec sweet spot for 10-30 cm scan distances
     * and matches what most scanners expect.
     */
    fun renderPng(matrix: QrCode.Matrix, scale: Int = 8, quietZone: Int = 4): ByteArray {
        require(scale >= 1) { "scale must be >= 1, got $scale" }
        require(quietZone >= 0) { "quietZone must be >= 0, got $quietZone" }
        val totalSize = (matrix.size + quietZone * 2) * scale
        val img = BufferedImage(totalSize, totalSize, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until totalSize) {
            for (x in 0 until totalSize) {
                val mx = (x / scale) - quietZone
                val my = (y / scale) - quietZone
                val dark = mx in 0 until matrix.size && my in 0 until matrix.size && matrix[my, mx]
                img.setRGB(x, y, if (dark) BLACK else WHITE)
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    /**
     * Write [matrix] as a PNG to [file]. Parent directories are created.
     * Returns the number of bytes written for the test assertion.
     */
    fun writePng(matrix: QrCode.Matrix, file: File, scale: Int = 8, quietZone: Int = 4): Long {
        file.parentFile?.mkdirs()
        val bytes = renderPng(matrix, scale, quietZone)
        file.writeBytes(bytes)
        return bytes.size.toLong()
    }

    /**
     * Variant of [writePng] that streams to an arbitrary [output] so tests
     * can assert the byte stream without touching the filesystem.
     */
    fun writePngToStream(matrix: QrCode.Matrix, output: OutputStream, scale: Int = 8, quietZone: Int = 4) {
        val bytes = renderPng(matrix, scale, quietZone)
        output.write(bytes)
        output.flush()
    }

    private fun halfBlockChar(top: Boolean, bottom: Boolean): Char = when {
        top && bottom -> '█' // full block
        top -> '▀' // upper half
        bottom -> '▄' // lower half
        else -> ' ' // empty
    }

    private fun quietZoneLine(width: Int): String = QUIET + " ".repeat(width) + QUIET + "\n"

    private const val QUIET = " "
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
