package com.letta.mobile.ui.screens.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QrMatrixGeometryTest {

    @Test
    fun `module size accounts for quiet zone on both sides`() {
        // 21 modules + 2 quiet-zone modules on each side = 25 total.
        val size = QrMatrixGeometry.moduleSizePx(matrixSize = 21, canvasSizePx = 250f, quietZoneModules = 2)
        assertEquals(10f, size, 0.001f)
    }

    @Test
    fun `zero quiet zone uses the full canvas for modules`() {
        val size = QrMatrixGeometry.moduleSizePx(matrixSize = 10, canvasSizePx = 100f, quietZoneModules = 0)
        assertEquals(10f, size, 0.001f)
    }

    @Test
    fun `top-left module sits exactly one quiet zone in from the canvas edge`() {
        val geometry = QrMatrixGeometry.moduleRect(
            matrixSize = 21,
            row = 0,
            col = 0,
            canvasSizePx = 250f,
            quietZoneModules = 2,
        )
        assertEquals(20f, geometry.x, 0.001f) // 2 quiet-zone modules * 10px
        assertEquals(20f, geometry.y, 0.001f)
        assertEquals(10f, geometry.size, 0.001f)
    }

    @Test
    fun `bottom-right module ends exactly at the quiet zone boundary`() {
        val matrixSize = 21
        val canvasSizePx = 250f
        val quietZoneModules = 2
        val geometry = QrMatrixGeometry.moduleRect(
            matrixSize = matrixSize,
            row = matrixSize - 1,
            col = matrixSize - 1,
            canvasSizePx = canvasSizePx,
            quietZoneModules = quietZoneModules,
        )
        val moduleSize = QrMatrixGeometry.moduleSizePx(matrixSize, canvasSizePx, quietZoneModules)
        val expectedFarEdge = canvasSizePx - quietZoneModules * moduleSize
        assertEquals(expectedFarEdge, geometry.x + geometry.size, 0.001f)
        assertEquals(expectedFarEdge, geometry.y + geometry.size, 0.001f)
    }

    @Test
    fun `adjacent modules are contiguous with no gaps or overlaps`() {
        val g0 = QrMatrixGeometry.moduleRect(matrixSize = 21, row = 5, col = 5, canvasSizePx = 250f)
        val g1 = QrMatrixGeometry.moduleRect(matrixSize = 21, row = 5, col = 6, canvasSizePx = 250f)
        assertEquals(g0.x + g0.size, g1.x, 0.001f)
        assertEquals(g0.y, g1.y, 0.001f)
    }

    @Test
    fun `out of range row throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            QrMatrixGeometry.moduleRect(matrixSize = 21, row = 21, col = 0, canvasSizePx = 250f)
        }
    }

    @Test
    fun `out of range col throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            QrMatrixGeometry.moduleRect(matrixSize = 21, row = 0, col = -1, canvasSizePx = 250f)
        }
    }

    @Test
    fun `non-positive matrix size throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            QrMatrixGeometry.moduleSizePx(matrixSize = 0, canvasSizePx = 100f)
        }
    }
}
