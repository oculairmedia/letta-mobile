package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * letta-mobile-3io8k: the drawer context card's few layout facts that are not on the [LettaDimens]
 * scale; spacing still comes from [LettaDimens.Space].
 */
object AgentContextDimens {
    /** The card's slim meter. */
    val cardBarHeight: Dp = 4.dp

    /** The desktop popover beside the sidebar card. */
    val popoverWidth: Dp = 360.dp
    val popoverMaxHeight: Dp = 620.dp

    /** How far below the card the desktop popover opens. */
    val popoverGap: Dp = 6.dp

    /** letta-mobile-joigh: room for the limit slider's widest label ("200k of 400k"), so the track never shifts. */
    val limitValueWidth: Dp = 84.dp

    /** letta-mobile-joigh: the limit status row's fixed height: a text button's, so its Compact action never moves the sheet. */
    val limitStatusHeight: Dp = 40.dp

    /** letta-mobile-joigh: the auto-compact tick on the sheet's context bar. */
    val autoCompactMarkerWidth: Dp = 2.dp
}
