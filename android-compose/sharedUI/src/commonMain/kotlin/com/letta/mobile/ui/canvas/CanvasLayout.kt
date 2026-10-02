package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the board arranges its chrome for the space it has.
 *
 * [EXPANDED] is the desktop and tablet board: the tool rail down the left, zoom in the actions
 * pill, a status line at the foot. [COMPACT] is the phone board: the tools become one bar along
 * the bottom edge within reach of a thumb, undo and redo move up beside the overflow menu (or, under
 * the phone's chat page, join the tool bar with it; see [CanvasHostChrome]), and zoom (which a phone
 * does by pinching) folds into that menu. [AUTO] picks by the board's own
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

/**
 * Host chrome along the foot of the window, measured from the window's bottom edge like the system
 * bars it joins: the phone's shared chat bar, whose rounded top the board runs on under. The board
 * draws under it; its own foot (the tool bar, a note's formatting) keeps above it. Like any inset,
 * what a parent has already padded and consumed is not padded again.
 */
internal val LocalCanvasChromeBottomInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** An entry a host puts at the top of the board's overflow (more) menu, above the board's own. */
@Immutable
internal class CanvasHostMenuEntry(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * How the page around the board wants the board's chrome laid out.
 *
 * The phone's shared chat page keeps the top of the board clear (letta-mobile-bglj6.1): with
 * [actionsInFoot] a compact board draws no actions pill; undo, redo and the overflow menu join the
 * tool bar at its foot, sharing and the sync status move into that menu, and [menu] carries what
 * the page's own header would have offered over the board (the agent switcher and menu). A wide
 * board keeps its header whatever the page asks.
 */
@Immutable
internal class CanvasHostChrome(
    val actionsInFoot: Boolean = false,
    val menu: List<CanvasHostMenuEntry> = emptyList(),
)

internal val LocalCanvasHostChrome = androidx.compose.runtime.compositionLocalOf { CanvasHostChrome() }

/**
 * The keyboard's inset as the board reads it; null reads the window's own (WindowInsets.ime).
 * Tests stand a keyboard in with this, as a desktop window has none.
 */
internal val LocalCanvasImeInsets = androidx.compose.runtime.compositionLocalOf<WindowInsets?> { null }

/** The layout for a board measured [widthPx] wide ([width] in dp); null until it has been measured. */
internal fun CanvasLayout.resolveMeasured(widthPx: Int, width: Dp): CanvasLayout? =
    resolve(width).takeIf { widthPx > 0 }

/** What the floating selection bar keeps clear of on the left: the tool rail, when there is one. */
internal fun CanvasLayout?.railClearance(): Dp = if (this == CanvasLayout.EXPANDED) RAIL_CLEARANCE else 0.dp

/** The tool rail's width and its inset. */
private val RAIL_CLEARANCE: Dp = 64.dp

/** Below this width the board is phone-sized: Material's compact window class. */
internal val COMPACT_CANVAS_WIDTH: Dp = 600.dp

/** The board's actions pill (top-right, or the header bar with a title). */
internal const val CANVAS_ACTIONS_TAG = "canvas-actions"

/** The phone board's tool bar at its foot. */
internal const val CANVAS_COMPACT_TOOLBAR_TAG = "canvas-compact-toolbar"

/** The overflow (more) button on the phone board's tool bar. */
internal const val CANVAS_FOOT_MORE_TAG = "canvas-foot-more"
