package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** letta-mobile-bglj6.1: the shared chat page's shell tokens (dock and its reply card). */
object ChatSurfaceDimens {
    /** The reply card floats over the canvas, so it lifts off it. */
    val dockedReplyElevation: Dp = 6.dp

    /** The reply card never covers more than this share of the canvas. */
    const val dockedReplyMaxHeightFraction: Float = 0.45f
}
