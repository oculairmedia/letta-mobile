package com.letta.mobile.ui.screens.pairing

/**
 * Pure pixel-geometry for rasterizing a [com.letta.mobile.qr.QrCode.Matrix]
 * onto a square canvas (letta-mobile-g2d2i, server-mode invite rendering).
 *
 * Kept free of any `androidx.compose.ui` types so it's testable as plain
 * JVM unit tests (no Compose test rule / instrumentation needed) — see
 * `QrMatrixGeometryTest`. [QrMatrixCanvas] converts these into
 * `androidx.compose.ui.geometry.Offset`/`Size` at draw time.
 */
data class ModuleGeometry(val x: Float, val y: Float, val size: Float)

object QrMatrixGeometry {

    /** Per-module edge length in px for a [matrixSize]x[matrixSize] matrix drawn into [canvasSizePx], with [quietZoneModules] of margin on each side. */
    fun moduleSizePx(matrixSize: Int, canvasSizePx: Float, quietZoneModules: Int = QUIET_ZONE_MODULES): Float {
        require(matrixSize > 0) { "matrixSize must be > 0, got $matrixSize" }
        require(canvasSizePx > 0f) { "canvasSizePx must be > 0, got $canvasSizePx" }
        require(quietZoneModules >= 0) { "quietZoneModules must be >= 0, got $quietZoneModules" }
        val totalModules = matrixSize + quietZoneModules * 2
        return canvasSizePx / totalModules
    }

    /** Top-left pixel + edge length for the module at ([row], [col]). */
    fun moduleRect(
        matrixSize: Int,
        row: Int,
        col: Int,
        canvasSizePx: Float,
        quietZoneModules: Int = QUIET_ZONE_MODULES,
    ): ModuleGeometry {
        require(row in 0 until matrixSize) { "row $row out of range [0, $matrixSize)" }
        require(col in 0 until matrixSize) { "col $col out of range [0, $matrixSize)" }
        val moduleSize = moduleSizePx(matrixSize, canvasSizePx, quietZoneModules)
        return ModuleGeometry(
            x = (col + quietZoneModules) * moduleSize,
            y = (row + quietZoneModules) * moduleSize,
            size = moduleSize,
        )
    }

    const val QUIET_ZONE_MODULES: Int = 2
}
