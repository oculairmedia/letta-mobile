package com.letta.mobile.ui.canvas

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize

/** One place to frame on a board: [bounds] in world units, on [canvasId] (null: whichever board). */
@Immutable
data class CanvasCameraTarget(
    val canvasId: String?,
    val bounds: Rect,
    /** Tells two requests for the same place apart, so asking again frames again. */
    val serial: Long,
) {
    /** Whether this target is for the board [boardId] (either side not naming one matches any). */
    fun isFor(boardId: String?): Boolean = canvasId == null || boardId == null || canvasId == boardId
}

/** The board a camera request is framed on: its canvas, its measured [size], and whether it has [loaded]. */
class CameraBoard(val canvasId: String?, val size: IntSize, val loaded: Boolean) {
    /** Loaded and measured: a fit can be worked out. */
    fun isReady(): Boolean = loaded && size.width > 0 && size.height > 0
}

/**
 * letta-mobile-bglj6.13: where something outside the board (the chat's "Show on canvas") asks its
 * camera to go. The board frames the [target] once it is loaded and measured, at no more than
 * 100% zoom, and then [consume]s it; a target for another board is dropped unframed.
 */
@Stable
class CanvasCameraRequest {
    var target: CanvasCameraTarget? by mutableStateOf(null)
        private set

    private var serial = 0L

    fun frame(canvasId: String?, bounds: Rect) {
        serial += 1
        target = CanvasCameraTarget(canvasId, bounds, serial)
    }

    /**
     * Frames [handled] (when there is one) on [board] once it is ready, at no more than 100% zoom,
     * handing the fit to [jump], then [consume]s it; a target for another board is consumed unframed.
     */
    internal fun frameOn(handled: CanvasCameraTarget?, board: CameraBoard, jump: (CanvasFit) -> Unit) {
        if (handled == null || !board.isReady()) return
        if (handled.isFor(board.canvasId)) CanvasViewportFit.fitOrNull(handled.bounds, board.size, maxScale = 1f)?.let(jump)
        consume(handled)
    }

    /** Clears [handled] unless a newer request replaced it meanwhile. */
    fun consume(handled: CanvasCameraTarget) {
        if (target == handled) target = null
    }
}
