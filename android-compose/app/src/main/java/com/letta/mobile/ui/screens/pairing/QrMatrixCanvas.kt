package com.letta.mobile.ui.screens.pairing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.letta.mobile.qr.QrCode

/**
 * Rasterizes a [QrCode.Matrix] into a Compose [Canvas] — one `drawRect` per
 * dark module — instead of allocating an `android.graphics.Bitmap` or
 * reusing `com.letta.mobile.qr.QrRenderer` (JDK-only: `java.awt.image` /
 * `javax.imageio`, unusable on Android). See letta-mobile-g2d2i.
 *
 * Geometry is delegated to [QrMatrixGeometry], a Compose-free pure-function
 * module so the module placement math has a JVM unit test
 * (`QrMatrixGeometryTest`) independent of a Compose test rule.
 */
@Composable
fun QrMatrixCanvas(
    matrix: QrCode.Matrix,
    modifier: Modifier = Modifier,
    quietZoneModules: Int = QrMatrixGeometry.QUIET_ZONE_MODULES,
    darkColor: Color = Color.Black,
    lightColor: Color = Color.White,
) {
    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        val canvasSizePx = size.minDimension
        drawRect(color = lightColor, size = Size(canvasSizePx, canvasSizePx))
        for (row in 0 until matrix.size) {
            for (col in 0 until matrix.size) {
                if (!matrix[row, col]) continue
                val geometry = QrMatrixGeometry.moduleRect(matrix.size, row, col, canvasSizePx, quietZoneModules)
                drawRect(
                    color = darkColor,
                    topLeft = Offset(geometry.x, geometry.y),
                    size = Size(geometry.size, geometry.size),
                )
            }
        }
    }
}
