package com.letta.mobile.ui.shell.pages.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.letta.mobile.ui.shell.tokens.ShellDimens
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Where each mascot stands on the greeting's stage: the centre of each tile as an offset from the
 * stage's centre, left to right, all tiles [tile] wide. The row is centred under the mark; [huddled]
 * draws it in close (the connection is unsettled). A stage too narrow for the normal gap shrinks the
 * gap, never the tiles, and never lets two tiles overlap.
 */
internal fun greetingSlots(count: Int, stageWidth: Dp, huddled: Boolean, tile: Dp = ShellDimens.homeGreetingMascot): List<Dp> {
    if (count <= 0) return emptyList()
    val wanted = if (huddled) LettaDimens.Space.xs else LettaDimens.Space.xl
    val room = if (count > 1) ((stageWidth - tile * count) / (count - 1)).coerceAtLeast(0.dp) else wanted
    val gap = min(wanted, room)
    val pitch = tile + gap
    val middle = (count - 1) / 2f
    return List(count) { index -> pitch * (index - middle) }
}

/** How far a cheering mascot hops toward its neighbour, and a concerned one leans. */
internal val GreetingHop: Dp = LettaDimens.Space.md
