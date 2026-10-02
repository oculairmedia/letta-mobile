package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * letta-mobile-bglj6.1: the tap target of a quiet timeline line (a "Thought" row, a run header, a
 * "Ran 2 commands" summary). It draws no state layer: a pointer's hover would otherwise paint a
 * filled block over the line, an inset the Android timeline never shows. The line lifts its own
 * label instead while [lifted] (hovered or pressed), and a pointer turns to a hand over it.
 */
@Stable
internal class QuietClick(val source: MutableInteractionSource, private val hovered: () -> Boolean, private val pressed: () -> Boolean) {
    val lifted: Boolean get() = hovered() || pressed()
}

@Composable
internal fun rememberQuietClick(): QuietClick {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    return remember(source) { QuietClick(source, { hovered }, { pressed }) }
}

internal fun Modifier.quietClickable(
    click: QuietClick,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = pointerHoverIcon(PointerIcon.Hand)
    .clickable(interactionSource = click.source, indication = null, onClickLabel = onClickLabel, onClick = onClick)
