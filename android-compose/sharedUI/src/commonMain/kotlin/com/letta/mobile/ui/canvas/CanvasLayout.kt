package com.letta.mobile.ui.canvas

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the board arranges its chrome for the space it has.
 *
 * [EXPANDED] is the desktop and tablet board: the tool rail down the left, zoom in the actions
 * pill, a status line at the foot. [COMPACT] is the phone board: the tools become one bar along
 * the bottom edge within reach of a thumb, undo and redo move up beside the overflow menu, and
 * zoom (which a phone does by pinching) folds into that menu. [AUTO] picks by the board's own
 * width, so a narrow desktop pane gets the compact layout as well.
 */
enum class CanvasLayout {
    AUTO,
    COMPACT,
    EXPANDED,
    ;

    /** The layout to use for a board [width] wide; [AUTO] resolves by [COMPACT_CANVAS_WIDTH]. */
    internal fun resolve(width: Dp): CanvasLayout = when (this) {
        AUTO -> if (width < COMPACT_CANVAS_WIDTH) COMPACT else EXPANDED
        else -> this
    }
}

/**
 * True inside a board laid out [CanvasLayout.COMPACT], for controls that shrink on a phone - the
 * property panel, the formatting bar - without the flag threaded through every call.
 */
internal val LocalCanvasCompact = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Below this width the board is phone-sized: Material's compact window class. */
internal val COMPACT_CANVAS_WIDTH: Dp = 600.dp
