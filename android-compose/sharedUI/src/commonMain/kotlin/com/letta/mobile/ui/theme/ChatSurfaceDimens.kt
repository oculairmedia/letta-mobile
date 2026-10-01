package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.ChatColumnMaxWidth

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

    /** How far the mascot badge rises above the open panel's top edge: half the disc. */
    val dockBadgeOverhang: Dp = ChatMascotDimens.dockBadge / 2

    /** The badge's lift off the panel (a soft shadow, no tonal tint). */
    val dockBadgeElevation: Dp = 3.dp

    /** The header strip under a badge: the disc's lower half and a little air below it. */
    val dockBadgeHeader: Dp = dockBadgeOverhang + 6.dp

    /** The collapsed dock's height (the mascot over its bar) before it is first measured. */
    val dockCollapsedHeightEstimate: Dp = 180.dp

    /** The collapsed dock's reply bubble: at most this wide (a share of the chat column)... */
    val collapsedBubbleMaxWidth: Dp = ChatColumnMaxWidth * 0.6f

    /** ...and this tall; a longer reply scrolls inside it. */
    val collapsedBubbleMaxHeight: Dp = 280.dp

    /** How far above the mascot tile's foot the bubble sits, so its tail points at the head. */
    val collapsedBubbleLift: Dp = 56.dp

    /** How far the bubble tucks over the mascot tile's empty margin, so its tail nearly touches the body. */
    val collapsedBubbleTuck: Dp = 16.dp

    /** The bubble's tail: how far it reaches out towards the mascot, and how tall its base is. */
    val collapsedBubbleTailWidth: Dp = 10.dp
    val collapsedBubbleTailHeight: Dp = 14.dp

    /**
     * How far the minimised dock's ambient halo reaches past the mascot and its bubble, so the
     * glow fades out on the canvas instead of ending at their edges.
     */
    val collapsedHaloBleed: Dp = 28.dp

    /** The reset-placement snap. */
    const val dockSnapMillis: Int = 220
}

/**
 * letta-mobile-bglj6.1.9: the Touch canvas's chat head, a Google-Messages-style bubble with the
 * agent's mascot, snapped to a screen edge over the canvas, and the reply popup it speaks through.
 */
object ChatHeadDimens {
    /** The head's disc. */
    val head: Dp = 64.dp

    /** The mascot's seat inside the disc (the character overflows the disc a little, as in the badge). */
    val seat: Dp = 84.dp

    /** The sphere drawn for an agent without a mascot. */
    val fallbackSphere: Dp = 44.dp

    /** Between the head and the screen edge it is snapped to. */
    val edgeMargin: Dp = LettaDimens.Space.md

    /**
     * Kept clear above the head's lane: the canvas's actions pill sits in the top-right corner
     * under the app bar.
     */
    val topClearance: Dp = 72.dp

    /** Kept clear under the head's lane: the canvas's own tool bar rides on top of the chat bar. */
    val bottomClearance: Dp = 76.dp

    /** Between the head and its popup. */
    val popupGap: Dp = LettaDimens.Space.xs

    /** The popup is at most this share of the screen's width... */
    const val popupMaxWidthFraction: Float = 0.8f

    /** ...and this tall, then it scrolls. */
    val popupMaxHeight: Dp = 320.dp

    /** The head's lift off the canvas. */
    val elevation: Dp = 6.dp

    /** The snap to an edge after a drag. */
    const val snapMillis: Int = 240

    /** How far the thinking halo glows past the head's edge. */
    val haloBleed: Dp = 22.dp

    /** The halo's peak strength, the share of its radius held solid, and its breath. */
    const val haloStrength: Float = 0.7f
    const val haloSolidFraction: Float = 0.45f
    const val haloBreathLow: Float = 0.55f
    const val haloBreathMillis: Int = 1200
}
