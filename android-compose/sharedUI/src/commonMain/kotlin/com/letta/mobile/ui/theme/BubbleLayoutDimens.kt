package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The minimised chat's layout controls: the canvas bubble's layout button, its menu and the strips
 * that resize the bar.
 */

/** The layout button, the slider rows and Reset: comfortable touch targets. */
internal val BubbleLayoutControlSize: Dp = 44.dp

/** The invisible strip straddling each side and the top of the bar that resizes it. */
internal val BubbleLayoutEdgeStrip: Dp = 10.dp

/** The layout menu's width: room for a slider row's label, value and track. */
internal val BubbleLayoutMenuWidth: Dp = 300.dp

/** How long the layout button stays once the pointer leaves the mascot: time to reach it. */
internal const val BUBBLE_LAYOUT_REVEAL_LINGER_MS: Long = 1_500L
