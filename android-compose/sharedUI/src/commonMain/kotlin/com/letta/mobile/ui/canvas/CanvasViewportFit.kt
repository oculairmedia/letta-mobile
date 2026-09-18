package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.canvas.CanvasSceneDocument
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.bounds

/** A camera that shows a whole region: the scale and the viewport offset to apply. */
internal data class CanvasFit(val scale: Float, val offset: Offset)

/**
 * "Zoom to fit" the way tldraw and Miro do it: the union of every element's and every note's
 * bounds, padded, scaled to the board and centred in it. Null when the board has nothing on it.
 */
internal object CanvasViewportFit {
    const val PADDING = 48f
    const val MIN_SCALE = 0.05f
    const val MAX_SCALE = 4f

    fun contentBounds(elements: List<Element>, documents: List<CanvasSceneDocument>): Rect? {
        val rects = elements.map { it.bounds() } +
            documents.mapNotNull { it.frame?.let { f -> Rect(f.x, f.y, f.x + f.width, f.y + f.height) } }
        if (rects.isEmpty()) return null
        return rects.reduce { acc, r ->
            Rect(minOf(acc.left, r.left), minOf(acc.top, r.top), maxOf(acc.right, r.right), maxOf(acc.bottom, r.bottom))
        }
    }

    /** The camera that fits [content] into a board of [boardWidth] x [boardHeight] screen px. */
    fun fit(content: Rect, boardWidth: Float, boardHeight: Float, padding: Float = PADDING): CanvasFit {
        val width = (content.width + padding * 2).coerceAtLeast(1f)
        val height = (content.height + padding * 2).coerceAtLeast(1f)
        val scale = minOf(boardWidth / width, boardHeight / height).coerceIn(MIN_SCALE, MAX_SCALE)
        val centre = content.center
        val offset = Offset(boardWidth / 2f - centre.x * scale, boardHeight / 2f - centre.y * scale)
        return CanvasFit(scale, offset)
    }
}
