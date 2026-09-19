package com.letta.mobile.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaDimens
import io.ak1.drawbox.SelectionChromeStyle

/**
 * The one selection look on the board.
 *
 * A drawn shape and a note are both things you can pick up, so they say "selected" the same way:
 * an accent outline a little outside the element, with a handle at each corner. The same
 * [SelectionChromeStyle] is handed to DrawBox for shapes and read here for note cards, so the two
 * cannot drift apart — before this, a shape got DrawBox's chrome and a note got a plain 1dp border,
 * and they read as two different states of two different apps.
 */
@Composable
internal fun canvasSelectionStyle(): SelectionChromeStyle = SelectionChromeStyle(
    padding = LettaDimens.Space.xs,
    handleSize = LettaDimens.Space.sm,
    cornerRadius = LettaDimens.Radius.sm,
    strokeWidth = LettaDimens.Stroke.hairline,
    accent = MaterialTheme.colorScheme.primary,
)

/**
 * Draws [style]'s chrome around this node's bounds.
 *
 * [scale] is the board's zoom. Stroke and handles are divided by it so the chrome keeps one
 * thickness on screen at every zoom, the way the shape chrome does — selection is chrome, not
 * part of the drawing, so it should not grow when you zoom in.
 */
@Composable
internal fun CanvasSelectionChrome(
    style: SelectionChromeStyle,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val safeScale = if (scale <= 0f) 1f else scale
        val pad = style.padding.toPx() / safeScale
        val stroke = (style.strokeWidth.toPx() / safeScale).coerceAtLeast(MIN_STROKE_PX)
        val handle = style.handleSize.toPx() / safeScale
        val radius = style.cornerRadius.toPx() / safeScale
        val topLeft = Offset(-pad, -pad)
        val outline = Size(size.width + pad * 2, size.height + pad * 2)

        drawRoundRect(
            color = style.accent,
            topLeft = topLeft,
            size = outline,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            style = Stroke(width = stroke),
        )
        // A handle at each corner of the outline, centred on it.
        val half = handle / 2f
        listOf(
            Offset(topLeft.x, topLeft.y),
            Offset(topLeft.x + outline.width, topLeft.y),
            Offset(topLeft.x, topLeft.y + outline.height),
            Offset(topLeft.x + outline.width, topLeft.y + outline.height),
        ).forEach { corner ->
            drawRect(
                color = style.accent,
                topLeft = Offset(corner.x - half, corner.y - half),
                size = Size(handle, handle),
            )
        }
    }
}

/** Below this the outline stops being visible at all on a zoomed-out board. */
private const val MIN_STROKE_PX = 1f
