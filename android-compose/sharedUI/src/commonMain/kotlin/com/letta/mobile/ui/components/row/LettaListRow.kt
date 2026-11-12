package com.letta.mobile.ui.components.row

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.letta.mobile.ui.theme.tokens.LettaRowDimens

/** Tooltip capability supplied by the enclosing host without coupling rows to host or shell packages. */
val LocalLettaRowTooltip = compositionLocalOf<@Composable (String, @Composable () -> Unit) -> Unit> {
    { _, content -> content() }
}

internal class LettaListRowInteraction(
    val source: MutableInteractionSource,
    val hovered: State<Boolean>,
    val focused: State<Boolean>,
    val pressed: State<Boolean>,
)

internal val LocalLettaListRowInteraction = compositionLocalOf<LettaListRowInteraction?> { null }

@Composable
internal fun rememberLettaListRowInteraction(): LettaListRowInteraction {
    val source = remember { MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState()
    val focused = source.collectIsFocusedAsState()
    val pressed = source.collectIsPressedAsState()
    return remember(source) { LettaListRowInteraction(source, hovered, focused, pressed) }
}

/** A compact selectable row rendering a [LettaListRowSpec]; every roster row uses this treatment. */
@Composable
fun LettaListRow(
    spec: LettaListRowSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn where the icon goes, instead of it: a row that swaps its icon for an action on hover. */
    leading: (@Composable () -> Unit)? = null,
    /** Drawn after the trailing label: a check, a checkbox, a drill-in chevron. */
    end: (@Composable () -> Unit)? = null,
) {
    val inherited = LocalLettaListRowInteraction.current
    val interaction = inherited ?: rememberLettaListRowInteraction()
    val hovered by interaction.hovered
    val focused by interaction.focused
    val pressed by interaction.pressed
    val active = spec.enabled && (hovered || focused || pressed)
    val background = rowBackground(spec.selected, active)
    val rowModifier = modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.small)
        .background(background)
        .rowInteraction(spec, interaction.source, inherited == null, onClick)
        .padding(horizontal = LettaRowDimens.spacing, vertical = LettaRowDimens.spacing)
    LocalLettaRowTooltip.current(spec.tooltip) {
        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaRowDimens.spacing),
        ) { RowContents(spec, leading, end) }
    }
}

@Composable
private fun rowBackground(selected: Boolean, active: Boolean): Color = when {
    selected && active -> MaterialTheme.colorScheme.surfaceContainerHighest
    selected -> MaterialTheme.colorScheme.surfaceContainerHigh
    active -> MaterialTheme.colorScheme.surfaceContainer
    else -> Color.Transparent
}

private fun Modifier.rowInteraction(
    spec: LettaListRowSpec,
    source: MutableInteractionSource,
    ownsHover: Boolean,
    onClick: () -> Unit,
): Modifier {
    val hover = if (ownsHover) Modifier.hoverable(source, enabled = spec.enabled) else Modifier
    val cursor = if (spec.enabled) PointerIcon.Hand else PointerIcon.Default
    val opacity = if (spec.muted || !spec.enabled) LettaRowDimens.disabledAlpha else 1f
    return then(hover)
        .pointerHoverIcon(cursor)
        .semantics(mergeDescendants = spec.description != null) {
            selected = spec.selected
            spec.description?.let { contentDescription = it }
        }
        .clickable(interactionSource = source, indication = null, enabled = spec.enabled, role = spec.role, onClick = onClick)
        .alpha(opacity)
}
