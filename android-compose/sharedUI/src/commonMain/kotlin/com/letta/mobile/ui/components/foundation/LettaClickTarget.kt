package com.letta.mobile.ui.components.foundation

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/**
 * How much room a [lettaClickable] claims, and the shape its press ripple is clipped to.
 *
 * [Standalone] is a target on its own (a cell, a card, an avatar): at least the platform's minimum
 * touch target (48 dp) of layout. [Inline] is a line or a dense row whose height its content and
 * rail already set (a timeline line, a chip strip, a stats strip): no minimum, so adopting the
 * modifier never changes the row's height.
 */
@Immutable
class LettaClickTarget(val shape: Shape = RectangleShape, val minimumTouchTarget: Boolean = true) {
    companion object {
        /** A target on its own: the platform minimum of layout, a rectangular ripple. */
        val Standalone: LettaClickTarget = LettaClickTarget()

        /** A line or a dense row: no minimum, a rectangular ripple. */
        val Inline: LettaClickTarget = LettaClickTarget(minimumTouchTarget = false)
    }
}
