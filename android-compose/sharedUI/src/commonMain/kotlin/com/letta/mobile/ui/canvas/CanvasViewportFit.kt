package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.data.canvas.CanvasSceneDocument
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Viewport
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

    /**
     * The camera that fits [content] into [board], or null when there is nothing to fit or no
     * board to fit it into yet.
     */
    fun fitOrNull(content: Rect?, board: IntSize, maxScale: Float = MAX_SCALE): CanvasFit? {
        if (content == null) return null
        if (board.width <= 0) return null
        if (board.height <= 0) return null
        return fit(content, Size(board.width.toFloat(), board.height.toFloat()), maxScale = maxScale)
    }

    /** The camera that fits [content] into a [board] of screen px. */
    fun fit(content: Rect, board: Size, padding: Float = PADDING, maxScale: Float = MAX_SCALE): CanvasFit {
        val width = (content.width + padding * 2).coerceAtLeast(1f)
        val height = (content.height + padding * 2).coerceAtLeast(1f)
        val scale = minOf(board.width / width, board.height / height).coerceIn(MIN_SCALE, maxScale)
        val centre = content.center
        val offset = Offset(board.width / 2f - centre.x * scale, board.height / 2f - centre.y * scale)
        return CanvasFit(scale, offset)
    }

    /**
     * The screen pan that brings [target] (board units) into view on a [board] of screen px, at the
     * current zoom, or null when no pan is needed. With [centre] the target always lands in the
     * middle of the board; otherwise the camera only moves when part of it is off the board.
     */
    fun panToShow(target: Rect, viewport: Viewport, board: IntSize, centre: Boolean): Offset? {
        if (board.width <= 0 || board.height <= 0) return null
        val topLeft = viewport.worldToScreen(target.topLeft)
        val bottomRight = viewport.worldToScreen(target.bottomRight)
        val onBoard = topLeft.x >= 0f && topLeft.y >= 0f &&
            bottomRight.x <= board.width && bottomRight.y <= board.height
        if (!centre && onBoard) return null
        val delta = Offset(board.width / 2f, board.height / 2f) - viewport.worldToScreen(target.center)
        return delta.takeIf { it.getDistance() >= 0.5f }
    }
}
