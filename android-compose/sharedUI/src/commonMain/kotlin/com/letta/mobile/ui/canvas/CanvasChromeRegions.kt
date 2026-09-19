package com.letta.mobile.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * The board's own controls, by where they are on screen.
 *
 * The board fills the pane and its chrome - the tool rail, the selection bar, the formatting bar,
 * an opened note - is drawn ON TOP of it. That is invisible to the pen, which is offered events by
 * position rather than by hit testing: anything inside the board's rectangle looked like drawing
 * surface, so while a freehand tool was selected the pen's presses over the rail were taken as
 * ink and the buttons underneath never heard them. The tool could not be changed with the pen
 * that was using it, and only with that tool, which is what made it look like the rail itself was
 * broken.
 *
 * The mouse never had the problem: its events are not offered to the canvas first, so the normal
 * top-most-wins hit testing applied and the rail simply received them.
 *
 * Bounds are read through providers rather than stored, so chrome that moves or resizes - a bar
 * that appears with a selection, a panel that grows - stays accurate without re-registering.
 */
class CanvasChromeRegions {
    private val regions = mutableListOf<() -> Rect?>()

    /** Registers [bounds]; call the returned function to remove it again. */
    fun register(bounds: () -> Rect?): () -> Unit {
        regions += bounds
        return { regions -= bounds }
    }

    /** True when [position] (in the board's own space) is over a control rather than the board. */
    fun contains(position: Offset): Boolean = regions.any { it()?.contains(position) == true }
}

/**
 * Registers this composable's bounds as board chrome for as long as it is on screen.
 *
 * Put it on anything the board draws over itself that a person is meant to press. Forgetting it
 * costs a control that works with every pointer except the pen holding a drawing tool.
 */
@Composable
internal fun Modifier.canvasChrome(regions: CanvasChromeRegions?): Modifier {
    if (regions == null) return this
    // Remembered, not a plain local. A local is a NEW variable on every recomposition: the
    // registry goes on reading the one it captured first while the layout callback writes to the
    // newest, so the bounds read as null for ever and the control protects nothing.
    val holder = remember { ChromeBounds() }
    DisposableEffect(regions, holder) {
        val unregister = regions.register { holder.bounds }
        onDispose { unregister() }
    }
    return onGloballyPositioned { holder.bounds = it.boundsInRoot() }
}

/** Where one control currently is. Plain, so moving a control does not recompose anything. */
private class ChromeBounds {
    var bounds: Rect? = null
}
