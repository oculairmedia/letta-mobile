package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import io.ak1.drawbox.domain.model.Viewport
import kotlin.math.roundToInt

// How a note card sits on the board (letta-mobile-bglj6.17): placed and scaled from the viewport in
// the layout and draw phases only, so a pan or zoom never recomposes a card.

/** The zoom, never zero or less (a viewport not laid out yet reads as 1). */
private fun Viewport.safeScale(): Float = if (scale <= 0f) 1f else scale

/**
 * Puts this box at [topLeft] (world units) on the board, less [screenInset] screen px up and left,
 * and scales it by the board's zoom about its top-left corner. The viewport is read in the layout
 * and draw phases only, so a pan or zoom re-places and re-scales the card without recomposing it.
 */
internal fun Modifier.onBoard(viewport: () -> Viewport, topLeft: () -> Offset, screenInset: Float = 0f): Modifier =
    offset {
        val at = viewport().worldToScreen(topLeft())
        IntOffset((at.x - screenInset).roundToInt(), (at.y - screenInset).roundToInt())
    }.graphicsLayer {
        val scale = viewport().scale
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0f, 0f)
    }

/**
 * Sizes this box to a card of [width] x [height] (world units, one px each before the zoom) plus the
 * selection chrome's margin on every side: [screenInset] screen px, so that divided by the zoom in the
 * card's own units. Read in layout only; the card inside keeps its own fixed size.
 */
internal fun Modifier.chromeMargin(viewport: () -> Viewport, screenInset: Float, width: Float, height: Float): Modifier =
    layout { measurable, _ ->
        val margin = screenInset / viewport().safeScale()
        val w = (width + 2 * margin).roundToInt().coerceAtLeast(0)
        val h = (height + 2 * margin).roundToInt().coerceAtLeast(0)
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(w, h) { placeable.place(0, 0) }
    }

/**
 * Puts the card inside the margin [chromeMargin] leaves around it: [screenInset] screen px, in the
 * card's own (zoomed) units. Read in layout only.
 */
internal fun Modifier.insideChromeMargin(viewport: () -> Viewport, screenInset: Float): Modifier =
    offset {
        val margin = (screenInset / viewport().safeScale()).roundToInt()
        IntOffset(margin, margin)
    }
