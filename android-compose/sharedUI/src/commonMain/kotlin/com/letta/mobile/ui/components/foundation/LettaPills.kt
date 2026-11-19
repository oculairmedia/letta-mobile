package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.pill.LettaPillColors
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens

/*
 * The pill family: every small label that sits beside content (a tag, a status, a count, a scope)
 * shares one shape (MaterialTheme.shapes.small), one inset and one type role, so two pills on one row
 * always agree. Pick the variant by what carries the colour:
 *
 *   LettaPill          a role colour tints the fill, the hairline and the words (tags, kinds)
 *   LettaTonalPill     a LettaPillColors container / on-container pair (a status, a badge)
 *   LettaOutlinedPill  no colour: a neutral, read-only scope
 *   LettaChip          the tonal pill as a control: it opens something, with a 48dp touch target
 *                      (laid out at its drawn height in a chip strip: LettaClickTarget.Inline)
 *
 * LettaStatusLabel (no container: a dot or a glyph, then the words) lives with LettaStatusDot.
 *
 * Never colour alone: every variant carries its words.
 */

/** A small tinted pill (tags, kinds, type labels): [color] tints its fill, hairline and words. */
@Composable
fun LettaPill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    PillSurface(
        container = color.copy(alpha = PILL_FILL_ALPHA),
        content = color,
        modifier = modifier,
        border = BorderStroke(LettaStrokeTokens.hairline, color.copy(alpha = PILL_BORDER_ALPHA)),
    ) { PillContent(text, icon) }
}

/** A pill on a container role pair: a status ("Active" on tertiaryContainer), a badge, a worktree name. */
@Composable
fun LettaTonalPill(text: String, colors: LettaPillColors, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    PillSurface(colors.container, colors.content, modifier, border = null) { PillContent(text, icon) }
}

/** A neutral read-only pill outlined in outlineVariant, for a scope or a property spelled out in words. */
@Composable
fun LettaOutlinedPill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    PillSurface(
        container = Color.Transparent,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        border = BorderStroke(LettaStrokeTokens.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) { PillContent(text, icon) }
}

/**
 * The tonal pill as a control: a neutral chip that opens something (the effort in force, then the
 * shared menu indicator as [LettaChipSlots.trailing]; a live mark such as a pulsing dot as
 * [LettaChipSlots.leading]). Its touch target is the platform minimum even though it draws small.
 *
 * [target] says how much room it claims: [LettaClickTarget.Standalone] (the default) lays the chip
 * out at least the minimum touch target tall; [LettaClickTarget.Inline] (a chip strip in a stack
 * whose gaps the stack sets) lays it out at its drawn height, and Compose still extends its touch
 * bounds to the minimum. Only the target's minimum applies: the ripple keeps the chip's shape.
 */
@Composable
fun LettaChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    slots: LettaChipSlots = LettaChipSlots.None,
    target: LettaClickTarget = LettaClickTarget.Standalone,
) {
    val minimum = if (target.minimumTouchTarget) LocalMinimumInteractiveComponentSize.current else Dp.Unspecified
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides minimum) {
        Surface(
            onClick = onClick,
            // A clickable Surface reserves LocalMinimumInteractiveComponentSize around the small pill.
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) { PillContent(text, icon = null, slots = slots) }
    }
}

@Composable
private fun PillSurface(
    container: Color,
    content: Color,
    modifier: Modifier,
    border: BorderStroke?,
    body: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
        border = border,
        content = body,
    )
}

@Composable
private fun PillContent(text: String, icon: ImageVector?, slots: LettaChipSlots = LettaChipSlots.None) {
    Row(
        modifier = Modifier.padding(horizontal = LettaSpaceTokens.sm, vertical = LettaSpaceTokens.hair),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.xs),
    ) {
        slots.leading?.invoke()
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(LettaControlTokens.iconSm)) }
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        slots.trailing?.invoke()
    }
}


private const val PILL_FILL_ALPHA = 0.12f
private const val PILL_BORDER_ALPHA = 0.18f
