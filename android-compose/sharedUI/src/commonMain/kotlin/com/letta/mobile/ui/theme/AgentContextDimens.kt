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
}
