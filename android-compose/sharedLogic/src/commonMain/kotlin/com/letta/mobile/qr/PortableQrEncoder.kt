package com.letta.mobile.qr

import com.letta.mobile.data.pairing.QrEncoder
import com.letta.mobile.data.pairing.QrModules

/**
 * [QrEncoder] backed by the common [QrCodeGenerator] at level M, so every target (browser
 * included) draws the same code without a platform QR library.
 */
object PortableQrEncoder : QrEncoder {
    /** The module grid for [content]; throws [QrContentTooLongException] when it cannot fit a QR symbol. */
    override fun encode(content: String): QrModules =
        when (val result = QrCodeGenerator.encode(content, QrErrorCorrection.M)) {
            is QrEncodeResult.Encoded -> result.matrix.toModules()
            is QrEncodeResult.ContentTooLong -> throw QrContentTooLongException(result.requiredBits, result.capacityBits)
        }
}

/**
 * Content too long for a version 40 QR symbol. The message carries sizes only, never the content,
 * so it is safe to show or log.
 */
class QrContentTooLongException(
    /** Bits the content needs. */
    val requiredBits: Int,
    /** Bits a version 40 symbol holds at the level used. */
    val capacityBits: Int,
) : IllegalArgumentException("The code is too long to show as a QR code ($requiredBits of at most $capacityBits bits).")
