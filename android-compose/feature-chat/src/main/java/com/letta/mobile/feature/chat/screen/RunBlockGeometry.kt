package com.letta.mobile.feature.chat.screen

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/**
 * letta-mobile-jqiu3: draws the content [distance] higher AND reports it [distance] shorter.
 *
 * `Modifier.offset` only moves the drawing; the layout keeps the original height, so the run body
 * that the completed disclosure pulls up under its header left [distance] of empty space at the
 * bottom of the newest row, directly above the composer. Measuring the pull-up out of the reported
 * height keeps the same drawn position and gives that space back.
 */
internal fun Modifier.pullUp(distance: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = distance.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - shift) {
        placeable.place(0, -shift)
    }
}
