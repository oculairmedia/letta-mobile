package com.letta.mobile.ui.canvas

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaDimens
import io.ak1.drawbox.SelectionChromeStyle
import io.ak1.drawbox.domain.model.ResizeHandle

/**
 * The one selection look on the board, and the one way to resize.
 *
 * A drawn shape and a note are both things you can pick up, so they say "selected" the same way and
 * are resized the same way: an accent outline just outside the element, carrying the eight
 * [ResizeHandle] positions — four corners, four edge midpoints — each one draggable. The same
 * [SelectionChromeStyle] is handed to DrawBox for shapes and read here for note cards, so the two
 * cannot drift apart.
 *
 * Before this, a shape got DrawBox's chrome while a note got a plain border and its own resize dot
 * in the corner: two selection systems on one board.
 */
@Composable
internal fun canvasSelectionStyle(): SelectionChromeStyle = SelectionChromeStyle(
    padding = LettaDimens.Space.xs,
    handleSize = LettaDimens.Space.sm,
    // Square, not rounded: DrawBox's chrome is a plain rectangle, and a rounded note outline beside
    // a square shape outline is the difference you notice without being able to name it.
    cornerRadius = 0.dp,
    strokeWidth = LettaDimens.Stroke.hairline,
    accent = MaterialTheme.colorScheme.primary,
)

/**
 * How far the chrome reaches beyond the element it wraps: the outline's offset plus half a handle,
 * since handles are centred on the outline. The host insets the chrome's box by this on every side
 * so the outer half of each handle is still inside it and can be grabbed.
 */
internal fun SelectionChromeStyle.chromeInset(): Dp = padding + handleSize / 2

/**
 * Chrome for an element of [contentWidth] x [contentHeight], in a box inset by [chromeInset] on
 * every side.
 *
 * [scale] is the board's zoom: the outline's stroke is divided by it so the chrome keeps one
 * thickness on screen at every zoom, the way the shape chrome does.
 *
 * Only the handles take pointer input. The chrome covers the element, so making the whole box a
 * hit target would swallow every press on it — which is exactly what it did the first time, and
 * cost the eraser its click.
 */
@Composable
internal fun CanvasSelectionChrome(
    style: SelectionChromeStyle,
    scale: Float,
    contentWidth: Dp,
    contentHeight: Dp,
    modifier: Modifier = Modifier,
    onResize: ((ResizeHandle, Offset) -> Unit)? = null,
    onResizeEnd: (() -> Unit)? = null,
) {
    val handleFill = MaterialTheme.colorScheme.surface
    val safeScale = if (scale <= 0f) 1f else scale
    val half = style.handleSize / 2
    val boxWidth = contentWidth + style.chromeInset() * 2
    val boxHeight = contentHeight + style.chromeInset() * 2

    Box(
        modifier = modifier
            .size(width = boxWidth, height = boxHeight)
            .semantics { contentDescription = "Selection chrome" }
            .drawBehind {
                val halfPx = half.toPx()
                val stroke = (style.strokeWidth.toPx() / safeScale).coerceAtLeast(MIN_STROKE_PX / safeScale)
                val handlePx = style.handleSize.toPx()
                drawRect(
                    color = style.accent,
                    topLeft = Offset(halfPx, halfPx),
                    size = Size(size.width - halfPx * 2, size.height - halfPx * 2),
                    style = Stroke(width = stroke),
                )
                handleCentresPx(size, halfPx).forEach { centre ->
                    val corner = Offset(centre.x - handlePx / 2f, centre.y - handlePx / 2f)
                    drawRect(color = handleFill, topLeft = corner, size = Size(handlePx, handlePx))
                    drawRect(
                        color = style.accent,
                        topLeft = corner,
                        size = Size(handlePx, handlePx),
                        style = Stroke(width = stroke),
                    )
                }
            },
    ) {
        if (onResize != null) {
            HANDLE_GRID.forEach { (handle, cell) ->
                val (col, row) = cell
                val centreX = when (col) {
                    0 -> half
                    1 -> boxWidth / 2
                    else -> boxWidth - half
                }
                val centreY = when (row) {
                    0 -> half
                    1 -> boxHeight / 2
                    else -> boxHeight - half
                }
                Box(
                    modifier = Modifier
                        .offset(x = centreX - style.handleSize, y = centreY - style.handleSize)
                        .size(style.handleSize * 2)
                        .semantics { contentDescription = "Resize ${handle.name}" }
                        .pointerInput(handle, onResize, onResizeEnd) {
                            detectDragGestures(
                                onDragEnd = { onResizeEnd?.invoke() },
                                onDragCancel = { onResizeEnd?.invoke() },
                            ) { change, delta ->
                                change.consume()
                                onResize(handle, delta)
                            }
                        },
                )
            }
        }
    }
}

/**
 * Where each handle sits, as (column, row) in a 3x3 grid with the middle cell left out: the element
 * itself lives there. Written out rather than derived, because index arithmetic over eight handles
 * in a nine-cell grid is the kind of cleverness that puts a handle in the wrong place.
 */
private val HANDLE_GRID = listOf(
    ResizeHandle.TopLeft to (0 to 0),
    ResizeHandle.Top to (1 to 0),
    ResizeHandle.TopRight to (2 to 0),
    ResizeHandle.Left to (0 to 1),
    ResizeHandle.Right to (2 to 1),
    ResizeHandle.BottomLeft to (0 to 2),
    ResizeHandle.Bottom to (1 to 2),
    ResizeHandle.BottomRight to (2 to 2),
)

private fun handleCentresPx(boxSize: Size, halfPx: Float): List<Offset> {
    val left = halfPx
    val top = halfPx
    val right = boxSize.width - halfPx
    val bottom = boxSize.height - halfPx
    val midX = (left + right) / 2f
    val midY = (top + bottom) / 2f
    return listOf(
        Offset(left, top), Offset(midX, top), Offset(right, top),
        Offset(left, midY), Offset(right, midY),
        Offset(left, bottom), Offset(midX, bottom), Offset(right, bottom),
    )
}

/** Below this the outline stops being visible at all on a zoomed-out board. */
private const val MIN_STROKE_PX = 1f
