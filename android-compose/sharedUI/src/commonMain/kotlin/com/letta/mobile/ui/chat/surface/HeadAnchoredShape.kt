package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.graphics.Shape
import com.letta.mobile.ui.theme.ChatHeadDimens
import com.letta.mobile.ui.theme.LettaDimens

/**
 * A card spoken from the head (the popup, the bubble's card): rounded all round but for the corner
 * nearest the head, which stays tight.
 */
internal fun headAnchoredShape(headOnRight: Boolean, below: Boolean): Shape {
    // Clockwise from the top left, as the shape takes them.
    val corners = Array(CORNERS) { CornerSize(LettaDimens.Radius.lg) }
    corners[anchorCorner(headOnRight, below)] = CornerSize(ChatHeadDimens.popupAnchorCorner)
    return AbsoluteRoundedCornerShape(corners[0], corners[1], corners[2], corners[3])
}

/** Which corner (clockwise from the top left) faces the head. */
private fun anchorCorner(headOnRight: Boolean, below: Boolean): Int {
    return when {
        below -> if (headOnRight) TOP_RIGHT else TOP_LEFT
        else -> if (headOnRight) BOTTOM_RIGHT else BOTTOM_LEFT
    }
}

/** A card's four corners, clockwise from the top left. */
private const val CORNERS = 4
private const val TOP_LEFT = 0
private const val TOP_RIGHT = 1
private const val BOTTOM_RIGHT = 2
private const val BOTTOM_LEFT = 3
