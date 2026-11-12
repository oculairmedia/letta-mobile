package com.letta.mobile.qr

import com.letta.mobile.data.pairing.QrModules

/**
 * An encoded QR symbol: a square grid of [size] x [size] modules without the quiet zone.
 * Immutable; renderers add the light border (four modules recommended) themselves.
 */
class QrMatrix internal constructor(
    /** Symbol version, 1-40. */
    val version: Int,
    /** Error-correction level the symbol was built with. */
    val errorCorrection: QrErrorCorrection,
    /** Data mask pattern applied, 0-7. */
    val mask: Int,
    private val modules: BooleanArray,
) {
    /** Side length in modules: 17 + 4 x [version]. */
    val size: Int = 17 + 4 * version

    /** True when the module at [row], [col] (both 0 until [size]) is dark. */
    fun isDark(row: Int, col: Int): Boolean {
        require(row in 0 until size && col in 0 until size) { "module ($row, $col) is outside a $size-module symbol" }
        return modules[row * size + col]
    }

    /** This symbol as the pairing UI's module grid. */
    fun toModules(): QrModules = QrModules(size, ::isDark)
}

/** Outcome of [QrCodeGenerator.encode]. */
sealed interface QrEncodeResult {
    /** The content fits; [matrix] is the smallest symbol that holds it at the requested level. */
    data class Encoded(val matrix: QrMatrix) : QrEncodeResult

    /**
     * The content does not fit even a version 40 symbol at the requested level. Carries sizes
     * only, never the content itself.
     */
    data class ContentTooLong(
        /** Bits the content needs in a version 40 symbol. */
        val requiredBits: Int,
        /** Data bits a version 40 symbol holds at the requested level. */
        val capacityBits: Int,
    ) : QrEncodeResult
}
