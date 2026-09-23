package com.letta.mobile.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Viewport
import io.ak1.drawbox.domain.model.bounds
import com.letta.mobile.data.canvas.CanvasSceneDocument
import kotlin.math.roundToInt

/**
 * Places [content] against [anchor] the way Miro floats an element's menu: centred just above it,
 * below it when there is no room above, and always kept on the board. With no anchor the content
 * sits top-centre under [topClearance], where the fixed bar used to be.
 *
 * Only the content is hit-testable; the full-board layout around it takes no input.
 */
@Composable
internal fun AnchoredToSelection(
    anchor: Rect?,
    modifier: Modifier = Modifier,
    topClearance: Dp = 64.dp,
    bottomClearance: Dp = 72.dp,
    /** Room kept free on the left, for the desktop tool rail the bar must not slide under. */
    startClearance: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = EDGE_MARGIN.roundToPx()
        val start = margin + startClearance.roundToPx()
        val gap = ANCHOR_GAP.roundToPx()
        val top = topClearance.roundToPx()
        val bottomLimit = height - bottomClearance.roundToPx() - placeable.height
        val (x, y) = if (anchor == null) {
            (width - placeable.width) / 2 to top
        } else {
            val centredX = (anchor.center.x - placeable.width / 2f).roundToInt()
            val maxX = (width - placeable.width - margin).coerceAtLeast(start)
            val above = anchor.top.roundToInt() - gap - placeable.height
            val below = anchor.bottom.roundToInt() + gap
            val preferredY = when {
                above >= top -> above
                below <= bottomLimit -> below
                // A selection taller than the board: hold the bar at the top of it.
                else -> top
            }
            // A selection panned off the bottom would put the bar there too, out of reach.
            centredX.coerceIn(start, maxX) to preferredY.coerceIn(top, bottomLimit.coerceAtLeast(top))
        }
        layout(width, height) { placeable.place(x, y) }
    }
}

/** Where [notes] sit on the board, moved by [offset] (a group drag in progress). */
internal fun noteRects(notes: List<CanvasSceneDocument>, offset: Offset): List<Rect> =
    notes.mapNotNull { doc -> doc.frame?.let { f -> Rect(f.x, f.y, f.x + f.width, f.y + f.height).translate(offset) } }

/**
 * Where the selection is on screen: the union of the selected drawn elements and notes, mapped
 * through [viewport]. Null when nothing is selected.
 */
internal fun selectionScreenRect(
    elements: List<Element>,
    selectedIds: Set<String>,
    noteRects: List<Rect>,
    viewport: Viewport,
): Rect? {
    val world = elements.filter { it.id in selectedIds }.map { it.bounds() } + noteRects
    if (world.isEmpty()) return null
    val union = world.reduce { acc, r ->
        Rect(minOf(acc.left, r.left), minOf(acc.top, r.top), maxOf(acc.right, r.right), maxOf(acc.bottom, r.bottom))
    }
    return Rect(viewport.worldToScreen(union.topLeft), viewport.worldToScreen(union.bottomRight))
}

private val EDGE_MARGIN = 8.dp
private val ANCHOR_GAP = 12.dp
