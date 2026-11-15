package io.ak1.drawbox

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * DrawBox's own default sizes, named once. A host overrides the selection chrome through
 * SelectionChromeStyle and the pick tolerance through its pickTolerance parameter; these are what it
 * gets when it does not.
 */

/** The selection box's default gap from an element's tight bounds. */
internal val SELECTION_PADDING: Dp = 6.dp

/** The default diameter of the round resize and rotation handles. */
internal val SELECTION_HANDLE_SIZE: Dp = 11.dp

/** The selection box's default corner radius. */
internal val SELECTION_CORNER_RADIUS: Dp = 8.dp

/** The default line thickness of the selection box and handle outlines. */
internal val SELECTION_STROKE_WIDTH: Dp = 1.5.dp

/** How near a handle a press still grabs it by default (a mouse; a host widens it for a finger). */
internal val SELECTION_HIT_RADIUS: Dp = 16.dp

/** How far above the selection box the rotation handle floats by default. */
internal val SELECTION_ROTATION_OFFSET: Dp = 28.dp

/** How near a line a press still picks it when the host gives no tolerance of its own. */
internal val DEFAULT_PICK_TOLERANCE: Dp = 12.dp

/** The square an image with no intrinsic size is laid out at. */
internal val IMAGE_FALLBACK_SIZE: Dp = 64.dp
