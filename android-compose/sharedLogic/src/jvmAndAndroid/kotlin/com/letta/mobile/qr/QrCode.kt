package com.letta.mobile.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * letta-mobile-gw0h1: small Kotlin facade over ZXing that produces a
 * 2D bit-matrix from a UTF-8 string. The wire format is `letta-qr-v1.<base64url-json>`
 * (≈150-180 bytes today, with headroom for future metadata), so the default
 * configuration — QR Code, byte mode, error-correction level M (15% recovery)
 * — gives us a comfortable version 5-6 QR with ample scanning slack in
 * production lighting.
 *
 * Why ZXing over a hand-written encoder: hand-written QR encoders are
 * notoriously subtle (mask scoring, BCH format-info, GF(256) Reed-Solomon
 * layout), and this bead's budget is 1-2 hours, not a re-derivation of
 * ISO/IEC 18004. ZXing is the standard, dependency-free-at-runtime
 * Java encoder; it has shipped in the Google Authenticator / Android
 * platform / countless commercial scanners for a decade. We use it here
 * as a renderer only — the wire format (encoder input) is the
 * letta-mobile-owned canonical JSON, NOT the QR-matrix bytes.
 *
 * The encoder contract is intentionally narrow: a 2D bool matrix the
 * renderer can paint. Tests live in :sharedLogic jvmTest.
 *
 * seealso: `reference/qr-pairing-protocol.md` §7 (transport encoding).
 */
object QrCode {

    data class Matrix(val size: Int, val darkRows: Array<BooleanArray>) {
        operator fun get(r: Int, c: Int): Boolean = darkRows[r][c]

        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Matrix &&
                    size == other.size &&
                    darkRows.contentDeepEquals(other.darkRows)
            )

        override fun hashCode(): Int = 31 * size + darkRows.contentDeepHashCode()
    }

    /**
     * Encode [content] (UTF-8) as a QR Code bit-matrix. Throws on
     * unrepresentable content (empty input, encoding failure) — the
     * protocol doc says "report an error rather than silently truncate".
     */
    fun encode(
        content: String,
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    ): Matrix {
        require(content.isNotEmpty()) { "QR content must not be empty" }
        val matrix: BitMatrix = try {
            MultiFormatWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                0, // width — auto from version
                0, // height — auto from version
                mapOf(
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.ERROR_CORRECTION to errorCorrection,
                    EncodeHintType.MARGIN to 1,
                ),
            )
        } catch (e: WriterException) {
            throw IllegalArgumentException("Failed to encode QR content (${e.message ?: "writer failure"})", e)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to encode QR content (${e.message ?: "encoder failure"})", e)
        }
        val size = matrix.width
        val out = Array(size) { BooleanArray(size) }
        for (r in 0 until size) {
            for (c in 0 until size) {
                // BitMatrix.get(x, y) takes (column, row) — confirmed against
                // the round-trip test (encoder → BufferedImage → decoder).
                out[r][c] = matrix.get(c, r)
            }
        }
        return Matrix(size, out)
    }
}
