package com.letta.mobile.ui.canvas

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/** One place to frame on a board: [bounds] in world units, on [canvasId] (null: whichever board). */
@Immutable
data class CanvasCameraTarget(
    val canvasId: String?,
    val bounds: Rect,
    /** Tells two requests for the same place apart, so asking again frames again. */
    val serial: Long,
)

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

    /** Clears [handled] unless a newer request replaced it meanwhile. */
    fun consume(handled: CanvasCameraTarget) {
        if (target == handled) target = null
    }
}
