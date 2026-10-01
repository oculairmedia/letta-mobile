package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** letta-mobile-bglj6.1: the shared chat page's shell tokens (the docked panel over the canvas). */
object ChatSurfaceDimens {
    /** The reply card floats over the canvas, so it lifts off it. */
    val dockedReplyElevation: Dp = 6.dp

    /** The docked panel opens at this share of the canvas height. */
    const val dockDefaultHeightFraction: Float = 0.45f

    /** The docked panel's default width: the chat column. */
    val dockDefaultWidth: Dp = 760.dp

    /** Smallest docked panel a resize allows. */
    val dockMinWidth: Dp = 320.dp
    val dockMinHeight: Dp = 220.dp

    /** Largest docked panel a resize allows (the canvas bounds it further). */
    val dockMaxWidth: Dp = 1400.dp
    val dockMaxHeight: Dp = 1600.dp

    /** Space kept between the docked panel and the canvas edges. */
    val dockMargin: Dp = 8.dp

    /** The invisible resize strip just outside each panel edge (mouse, pen). */
    val dockResizeEdge: Dp = 6.dp

    /** The resize hit area just outside each panel corner. */
    val dockResizeCorner: Dp = 16.dp

    /** The visible resize grip (touch), just above the panel's top-right corner. */
    val dockResizeGrip: Dp = 24.dp

    /** How far one accessibility move or resize action changes the docked panel. */
    val dockAccessibilityStep: Dp = 32.dp

    /** The grip pill in the panel header. */
    val dockGripWidth: Dp = 32.dp
    val dockGripHeight: Dp = 4.dp

    /** The collapsed bar's height before it is first measured. */
    val dockCollapsedHeightEstimate: Dp = 96.dp

    /** The reset-placement snap. */
    const val dockSnapMillis: Int = 220
}
