package com.letta.mobile.ui.canvas.boards

import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.model.CanvasId

/**
 * Opens a stored board by its id, for a view that shows one board wherever it is placed (a canvas
 * module in a shell lane). The source decides which store the id is read from.
 */
fun interface CanvasBoardSource {
    /** A session over the stored board [canvasId], or null when there is no such board. */
    suspend fun open(canvasId: CanvasId): CanvasSession?
}

/**
 * Where a view that shows a board by id opens it. Null (the default) is a host that cannot open
 * boards by id; such a view shows only boards already live in the host's
 * [com.letta.mobile.ui.canvas.LocalCanvasSessionRegistry].
 */
val LocalCanvasBoardSource = staticCompositionLocalOf<CanvasBoardSource?> { null }
