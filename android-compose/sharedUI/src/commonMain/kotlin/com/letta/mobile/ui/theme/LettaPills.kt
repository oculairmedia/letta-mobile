package com.letta.mobile.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.ui.theme.pill.LettaPillColors

/*
 * The pill family: every small label that sits beside content (a tag, a status, a count, a scope)
 * shares one shape (LettaDimens.Radius.sm), one inset and one type role, so two pills on one row
 * always agree. Pick the variant by what carries the colour:
 *
 *   LettaPill          a role colour tints the fill, the hairline and the words (tags, kinds)
 *   LettaTonalPill     a LettaPillColors container / on-container pair (a status, a badge)
 *   LettaOutlinedPill  no colour: a neutral, read-only scope
 *   LettaChip          the tonal pill as a control: it opens something, with a 48dp touch target
 *   LettaStatusLabel   no container: a dot or a glyph, then the words, in one colour
 *
 * Never colour alone: every variant carries its words. They live beside the tokens (not in
 * ui.components) so any page can use them through the theme import it already has.
 */

/** A small tinted pill (tags, kinds, type labels): [color] tints its fill, hairline and words. */
@Composable
fun LettaPill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    PillSurface(
        container = color.copy(alpha = PILL_FILL_ALPHA),
        content = color,
        modifier = modifier,
        border = BorderStroke(LettaDimens.Stroke.hairline, color.copy(alpha = PILL_BORDER_ALPHA)),
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
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) { PillContent(text, icon) }
}

/**
 * The tonal pill as a control: a neutral chip that opens something (the effort in force, then a
 * chevron as [trailingIcon]). Its touch target is the platform minimum even though it draws small.
 */
@Composable
fun LettaChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, trailingIcon: ImageVector? = null) {
    Surface(
        onClick = onClick,
        // A clickable Surface reserves the platform's minimum touch target around the small pill.
        modifier = modifier,
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) { PillContent(text, icon = null, trailingIcon = trailingIcon) }
}

/**
 * A status in words after a dot, or after [icon] when the tone has a glyph, all in [color] and with
 * no container ("✓ Connected", "• Syncing").
 */
@Composable
fun LettaStatusLabel(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(LettaDimens.Control.iconSm))
        } else {
            Box(Modifier.size(LettaDimens.Space.sm).background(color, CircleShape))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Surface(modifier = modifier, shape = PillShape, color = container, contentColor = content, border = border, content = body)
}

@Composable
private fun PillContent(text: String, icon: ImageVector?, trailingIcon: ImageVector? = null) {
    Row(
        modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.iconSm)) }
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailingIcon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.iconSm)) }
    }
}

/** The family's one shape. */
private val PillShape = RoundedCornerShape(LettaDimens.Radius.sm)

private const val PILL_FILL_ALPHA = 0.12f
private const val PILL_BORDER_ALPHA = 0.18f
