package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import com.letta.mobile.data.canvas.CanvasBackgroundPattern

/**
 * One tile of the board's background pattern, for DrawBox's `setBackgroundPattern`. DrawBox
 * rasterises the painter at its intrinsic size and repeats it under the viewport transform, so a
 * tile of [CanvasBackgroundPattern.spacing] world units is a grid that pans and zooms with the
 * board. Null for `none`.
 */
fun CanvasBackgroundPattern.painter(): Painter? {
    val color = parseHexColor(colorHex) ?: Color(0xFF9CA3AF)
    val size = spacing.coerceAtLeast(MIN_SPACING)
    return when (kind) {
        CanvasBackgroundPattern.GRID -> PatternTilePainter(size, color, PatternTilePainter.Shape.GRID)
        CanvasBackgroundPattern.DOTS -> PatternTilePainter(size, color, PatternTilePainter.Shape.DOTS)
        CanvasBackgroundPattern.LINES -> PatternTilePainter(size, color, PatternTilePainter.Shape.LINES)
        else -> null
    }
}

/** The tile's tint, the colour DrawBox filters the rasterised tile with. */
fun CanvasBackgroundPattern.tint(): Color = parseHexColor(colorHex) ?: Color(0xFF9CA3AF)

internal class PatternTilePainter(
    private val spacing: Float,
    private val color: Color,
    private val shape: Shape,
) : Painter() {
    enum class Shape { GRID, DOTS, LINES }

    override val intrinsicSize: Size get() = Size(spacing, spacing)

    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        val line = (spacing / LINE_DIVISOR).coerceIn(MIN_LINE, MAX_LINE)
        when (shape) {
            Shape.GRID -> {
                drawLine(color, Offset(0f, 0f), Offset(w, 0f), strokeWidth = line)
                drawLine(color, Offset(0f, 0f), Offset(0f, h), strokeWidth = line)
            }
            Shape.LINES -> drawLine(color, Offset(0f, 0f), Offset(w, 0f), strokeWidth = line)
            Shape.DOTS -> drawCircle(color, radius = (line * DOT_RADIUS_FACTOR), center = Offset(w / 2f, h / 2f), style = Stroke(width = line))
        }
    }
}

private const val MIN_SPACING = 4f
private const val LINE_DIVISOR = 32f
private const val MIN_LINE = 0.5f
private const val MAX_LINE = 2f
private const val DOT_RADIUS_FACTOR = 1.2f
