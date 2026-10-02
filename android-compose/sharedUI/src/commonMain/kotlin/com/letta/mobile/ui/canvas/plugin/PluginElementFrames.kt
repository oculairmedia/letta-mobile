package com.letta.mobile.ui.canvas.plugin

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.ui.canvas.resizedBy
import io.ak1.drawbox.domain.model.ResizeHandle

/**
 * Where a plugin element sits on the board, in world units. The frame is drawn verbatim (no auto-fit
 * in v1); one that a peer or an older build wrote out of range is brought back into it here rather
 * than breaking the layout of the board: a missing frame gets the default size at the origin, an
 * edge that is not a finite positive number gets the default, and every edge is held to
 * [MIN_EDGE]..[MAX_EDGE].
 */
internal object PluginElementFrames {
    const val DEFAULT_WIDTH = 320f
    const val DEFAULT_HEIGHT = 240f
    const val MIN_EDGE = 48f
    const val MAX_EDGE = 16_384f

    fun boardFrameOf(element: CanvasPluginElement): CanvasDocumentFrame = sanitized(element.frame)

    fun sanitized(frame: CanvasDocumentFrame?): CanvasDocumentFrame {
        if (frame == null) return CanvasDocumentFrame(0f, 0f, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        return CanvasDocumentFrame(
            x = frame.x.finiteOr(0f),
            y = frame.y.finiteOr(0f),
            width = edge(frame.width, DEFAULT_WIDTH),
            height = edge(frame.height, DEFAULT_HEIGHT),
        )
    }

    /** The world-space bounds of [elements], for zoom-to-fit and camera requests. */
    fun boundsOf(elements: List<CanvasPluginElement>): List<Rect> = elements.map { element ->
        val frame = boardFrameOf(element)
        Rect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height)
    }

    private fun edge(value: Float, default: Float): Float =
        (if (value.isFinite() && value > 0f) value else default).coerceIn(MIN_EDGE, MAX_EDGE)

    private fun Float.finiteOr(default: Float): Float = if (isFinite()) this else default
}

/**
 * An element's frame while a person moves or resizes it: what is drawn follows the gesture, and the
 * board's frame takes over again once it ends.
 */
@Stable
internal class PluginElementGesture(initial: CanvasDocumentFrame) {
    var frame: CanvasDocumentFrame by mutableStateOf(initial)
        private set

    var active: Boolean by mutableStateOf(false)
        private set

    /** The board's frame for the element; ignored while a gesture holds it. */
    fun sync(stored: CanvasDocumentFrame) {
        if (!active) frame = stored
    }

    fun drag(delta: Offset) {
        active = true
        frame = frame.copy(x = frame.x + delta.x, y = frame.y + delta.y)
    }

    fun resize(handle: ResizeHandle, delta: Offset) {
        active = true
        frame = frame.resizedBy(handle, delta)
    }

    /** Ends the gesture: the frame to commit, or null when it did not move from [stored]. */
    fun end(stored: CanvasDocumentFrame): CanvasDocumentFrame? {
        active = false
        return frame.takeIf { it != stored }
    }
}
