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

    /** The bubble's tail, hanging from its bottom edge down to the mascot: half its base, and its height. */
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
     * Kept clear above the head's lane: the status bar over the board's top edge (the phone's
     * canvas mode keeps that edge otherwise clear), and a wide board's actions pill.
     */
    val topClearance: Dp = 72.dp

    /** Kept clear under the head's lane: the canvas's own tool bar rides on top of the chat bar. */
    val bottomClearance: Dp = 76.dp

    /** Between the head and its popup. */
    val popupGap: Dp = LettaDimens.Space.xs

    /**
     * The popup is at most this share of the screen's width, and never wider than [popupMaxWidth]:
     * a speech bubble beside the head, not a page over the board.
     */
    const val popupMaxWidthFraction: Float = 0.62f
    val popupMaxWidth: Dp = 280.dp

    /** ...and this tall (a few lines), then it scrolls under its edge fades. */
    val popupMaxHeight: Dp = 112.dp

    /** The popup's edge fades while its text scrolls. */
    val popupFadeLength: Dp = LettaDimens.Space.lg

    /** Around the popup's text: tight, as a bubble's. */
    val popupPaddingHorizontal: Dp = LettaDimens.Space.sm
    val popupPaddingVertical: Dp = LettaDimens.Space.sm

    /** The head's lift off the canvas. */
    val elevation: Dp = 6.dp

    /** The snap to an edge after a drag. */
    const val snapMillis: Int = 240
}
