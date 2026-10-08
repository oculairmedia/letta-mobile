package com.letta.mobile.ui.chat.surface.sendlift

import androidx.compose.ui.geometry.Rect

/**
 * letta-mobile-86njl.1: everything the send-lift harness saw after one 16 ms frame of the Touch
 * page, in px at density 1. [t] is milliseconds since the send frame (the first frame after the
 * tap is 0, the baseline frame before it is -16).
 */
internal data class SendFrame(
    val t: Int,
    /** The real prompt row's bubble (its test-tagged node), null until the row is composed. */
    val promptBubble: Rect?,
    /** The send-flight ghost, null when none is up. */
    val ghost: Rect?,
    /** The sent prompt's whole list item (leading space and copy button included). */
    val promptSlot: Rect?,
    /** The newest row that is not the sent prompt: the one the opening slot pushes up. */
    val olderRow: Rect?,
    val composerField: Rect,
    /** The part of the list the person sees: above the composer bar. */
    val viewport: Rect,
    /** The height of the space between the list and the bar (the companion row). */
    val companionRowHeight: Float,
    val firstVisibleItemIndex: Int,
    val scrollOffset: Int,
    val scrollCommands: Int,
    /** Timeline row compositions (first or re-) since the previous frame, all rows together. */
    val rowCompositions: Int,
    val chevronCount: Int,
    /** The departing draft text of the sent prompt; the redesign (letta-mobile-86njl.2) adds it. */
    val departingTextCount: Int,
    /** The scroll-to-latest button is on screen. */
    val latestButton: Boolean = false,
) {
    val ghostCount: Int get() = if (ghost == null) 0 else 1

    /** What the person sees as the prompt: the ghost while it flies, else the row's bubble. */
    val visiblePrompt: Rect? get() = ghost ?: promptBubble

    val atNewestEdge: Boolean get() = firstVisibleItemIndex == 0 && scrollOffset == 0

    override fun toString() =
        "t=$t ghost=${ghost.fmt()} bubble=${promptBubble.fmt()} slot=${promptSlot.fmt()} older=${olderRow.fmt()} " +
            "field=${composerField.fmt()} companion=$companionRowHeight first=$firstVisibleItemIndex/$scrollOffset " +
            "scrolls=$scrollCommands composed=$rowCompositions chevrons=$chevronCount departing=$departingTextCount latestButton=$latestButton"
}

private fun Rect?.fmt(): String =
    if (this == null) "-" else "[${left.toInt()},${top.toInt()} ${width.toInt()}x${height.toInt()}]"
