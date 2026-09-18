package com.letta.mobile.data.canvas

import kotlin.math.sqrt

/** A point a connector end can snap to: on [targetId], at [side] of it, in world units. */
data class CanvasSnapAnchor(
    val x: Float,
    val y: Float,
    val targetId: String,
    val side: String,
    /** True when the target is a block document (note or text), false for a drawn shape. */
    val isDocument: Boolean,
) {
    companion object {
        const val LEFT = "left"
        const val TOP = "top"
        const val RIGHT = "right"
        const val BOTTOM = "bottom"
        const val CENTER = "center"
    }
}

/**
 * Where a connector end snaps and how it stays attached, the way Miro and tldraw bind arrows:
 * edge midpoints and the centre of a rectangle are the anchors, the nearest one within the snap
 * radius wins, and a bound end follows its target when the target moves.
 */
object CanvasSnap {
    /** The five anchors of an axis-aligned box: four edge midpoints and the centre. */
    fun anchorsOf(x: Float, y: Float, width: Float, height: Float, targetId: String, isDocument: Boolean): List<CanvasSnapAnchor> {
        val cx = x + width / 2f
        val cy = y + height / 2f
        return listOf(
            CanvasSnapAnchor(x, cy, targetId, CanvasSnapAnchor.LEFT, isDocument),
            CanvasSnapAnchor(cx, y, targetId, CanvasSnapAnchor.TOP, isDocument),
            CanvasSnapAnchor(x + width, cy, targetId, CanvasSnapAnchor.RIGHT, isDocument),
            CanvasSnapAnchor(cx, y + height, targetId, CanvasSnapAnchor.BOTTOM, isDocument),
            CanvasSnapAnchor(cx, cy, targetId, CanvasSnapAnchor.CENTER, isDocument),
        )
    }

    fun anchorsOf(frame: CanvasDocumentFrame, documentId: String): List<CanvasSnapAnchor> =
        anchorsOf(frame.x, frame.y, frame.width, frame.height, documentId, isDocument = true)

    /** The anchor within [radius] world units of ([px], [py]) that is closest, or null. */
    fun nearest(px: Float, py: Float, anchors: List<CanvasSnapAnchor>, radius: Float): CanvasSnapAnchor? {
        var best: CanvasSnapAnchor? = null
        var bestDistance = radius
        for (anchor in anchors) {
            val dx = anchor.x - px
            val dy = anchor.y - py
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = anchor
            }
        }
        return best
    }

    /** The point on [frame] that [side] names, for a bound end following a moved document. */
    fun anchorOn(frame: CanvasDocumentFrame, side: String): Pair<Float, Float> {
        val cx = frame.x + frame.width / 2f
        val cy = frame.y + frame.height / 2f
        return when (side) {
            CanvasSnapAnchor.LEFT -> frame.x to cy
            CanvasSnapAnchor.TOP -> cx to frame.y
            CanvasSnapAnchor.RIGHT -> (frame.x + frame.width) to cy
            CanvasSnapAnchor.BOTTOM -> cx to (frame.y + frame.height)
            else -> cx to cy
        }
    }
}
