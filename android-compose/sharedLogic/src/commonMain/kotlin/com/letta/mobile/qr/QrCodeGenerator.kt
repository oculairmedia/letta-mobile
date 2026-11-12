package com.letta.mobile.qr

/**
 * Portable QR Code encoder (ISO/IEC 18004 Model 2) in common code, so every target, the browser
 * included, can draw a code without a platform library. Text is encoded as one alphanumeric
 * segment when it uses only the 45-character QR set, otherwise as UTF-8 bytes (with a UTF-8 ECI
 * header when it contains non-ASCII characters). The smallest of versions 1-40 that fits is chosen
 * and the data mask is picked by the standard penalty rules.
 */
object QrCodeGenerator {
    /** Encode [text] at [errorCorrection]; never throws for any input string. */
    fun encode(text: String, errorCorrection: QrErrorCorrection = QrErrorCorrection.M): QrEncodeResult =
        encode(text, errorCorrection, mask = null)

    /** As [encode], with a fixed [mask] (0-7) instead of the penalty-chosen one when not null. */
    internal fun encode(text: String, errorCorrection: QrErrorCorrection, mask: Int?): QrEncodeResult {
        val data = QrData.of(text)
        val version = (QrVersionTables.MIN_VERSION..QrVersionTables.MAX_VERSION).firstOrNull { data.fits(it, errorCorrection) }
            ?: return QrEncodeResult.ContentTooLong(
                requiredBits = data.bitLength(QrVersionTables.MAX_VERSION),
                capacityBits = QrData.capacityBits(QrVersionTables.MAX_VERSION, errorCorrection),
            )
        val codewords = QrReedSolomon.interleave(data.codewords(version, errorCorrection), version, errorCorrection)
        return QrEncodeResult.Encoded(build(version, errorCorrection, codewords, mask))
    }

    private fun build(version: Int, level: QrErrorCorrection, codewords: IntArray, fixedMask: Int?): QrMatrix {
        val base = QrGrid(version)
        base.drawFunctionPatterns(level)
        base.placeCodewords(codewords)
        val mask = fixedMask ?: (0 until MASK_COUNT).minBy { QrMaskPenalty.score(masked(base, level, it)) }
        return QrMatrix(version, level, mask, masked(base, level, mask).dark.copyOf())
    }

    private fun masked(base: QrGrid, level: QrErrorCorrection, mask: Int): QrGrid = base.copy().apply {
        applyMask(mask)
        drawFormat(level, mask)
    }

    private const val MASK_COUNT = 8
}
