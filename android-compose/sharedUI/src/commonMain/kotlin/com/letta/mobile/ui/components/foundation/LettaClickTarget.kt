package com.letta.mobile.ui.components.foundation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
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

        /**
         * A line or a dense row: no minimum, a rectangular ripple. For a row a pointer hovers (a
         * timeline line, a link-like label) use [lettaRowTarget], whose hover and press are rounded.
         */
        val Inline: LettaClickTarget = LettaClickTarget(minimumTouchTarget = false)
    }
}

/**
 * An inline target whose hover, focus and press state is drawn in the small shape token, the shape
 * every clickable row's highlight takes (LettaListRow's): a timeline line, a disclosure header, a
 * small text action. No minimum, so the row keeps its height.
 */
@Composable
fun lettaRowTarget(): LettaClickTarget {
    val shape = MaterialTheme.shapes.small
    return remember(shape) { LettaClickTarget(shape, minimumTouchTarget = false) }
}

/** An inline target clipped to [shape]: a rounded cell (an image) whose press must not square it off. */
@Composable
fun lettaInlineTarget(shape: Shape): LettaClickTarget = remember(shape) { LettaClickTarget(shape, minimumTouchTarget = false) }
