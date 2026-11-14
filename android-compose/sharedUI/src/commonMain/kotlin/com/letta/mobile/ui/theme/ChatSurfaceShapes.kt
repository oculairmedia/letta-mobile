package com.letta.mobile.ui.theme

import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/*
 * The chat surface's shapes (composer, docked panel, canvas bubble, subagent sheets), by role, on the
 * LettaDimens.Radius scale: a chip and a panel never share a radius, so the panel does not read as an
 * oversized chip. Shapes whose corner animates take the animated value and keep the geometry here.
 */

/** Composer chips, attachment thumbnails, command chips, the dock's grip, a command card. */
internal val ChatChipShape: Shape = RoundedCornerShape(LettaDimens.Radius.sm)

/** Popovers, suggestion lists, the background process shelf, a preview frame. */
internal val ChatCardShape: Shape = RoundedCornerShape(LettaDimens.Radius.md)

/** The composer's prompt card, the touch input tray, the docked panel at rest. */
internal val ChatPanelShape: Shape = RoundedCornerShape(LettaDimens.Radius.lg)

/**
 * The docked panel part-way through a morph into the full page: [roundness] 1 is the panel at rest
 * ([ChatPanelShape]), 0 is the page's square corners.
 */
internal fun chatPanelMorphShape(roundness: Float): Shape = RoundedCornerShape(LettaDimens.Radius.lg * roundness)

/** A touch sheet's row, whose corner eases between its resting and pressed radius. */
internal fun chatSheetRowShape(corner: Dp): Shape = RoundedCornerShape(corner)

/** The touch composer bar: flush to the screen's sides and bottom, so only its top corners round. */
internal fun chatBottomBarShape(corner: Dp): Shape = RoundedCornerShape(topStart = corner, topEnd = corner)

/** An agent orb: a rounded square whose corner the caller picks from LettaDimens.Radius. */
internal fun agentOrbShape(corner: Dp): Shape = RoundedCornerShape(corner)

/**
 * A card spoken from the canvas head (the popup, the bubble's card): rounded all round at the
 * [ChatPanelShape] radius but for the corner nearest the head, which stays tight. [below] puts the
 * card under the head, so the tight corner is on its top edge; [headOnRight] puts it on the right.
 */
internal fun headAnchoredShape(headOnRight: Boolean, below: Boolean): Shape {
    val panel = CornerSize(LettaDimens.Radius.lg)
    val anchor = CornerSize(ChatHeadDimens.popupAnchorCorner)
    val top = if (below) anchor else panel
    val bottom = if (below) panel else anchor
    return AbsoluteRoundedCornerShape(
        topLeft = if (headOnRight) panel else top,
        topRight = if (headOnRight) top else panel,
        bottomRight = if (headOnRight) bottom else panel,
        bottomLeft = if (headOnRight) panel else bottom,
    )
}
