package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.tokens.LettaControlTokens

/**
 * The one way to draw a glyph: a Material [Icon] at a size from the scale. Replaces raw `Icon(` calls
 * with their own `Modifier.size(...)` (18, 13, and Material's bare 24 dp all coexisted).
 *
 * Take [imageVector] from `LettaIcons`, not `Lucide.*` or Material `Icons.*`, so one concept has one
 * glyph. [size] is [LettaControlTokens.icon] by default; pass [LettaControlTokens.iconSm] for a glyph
 * inside a small control. [contentDescription] is null when the glyph only decorates words beside it.
 */
@Composable
fun LettaIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = LettaControlTokens.icon,
    tint: Color = LocalContentColor.current,
) {
    Icon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier.size(size), tint = tint)
}

/**
 * The one icon button: a Material [IconButton] laid out and drawn at [size] around a [LettaIcon] at
 * [iconSize]. Its hit area is never smaller than the platform's minimum touch target (48 dp): Compose
 * extends a smaller clickable's touch bounds to it, so a compact button stays easy to hit without
 * pushing its neighbours apart. Replaces `IconButton` calls sized 48, 28, 20 dp and eight other
 * sizes.
 *
 * [size] is [LettaControlTokens.iconButton] by default; use [LettaControlTokens.iconButtonSm] for a
 * glyph inside another control (a tab's close cross) and [LettaControlTokens.iconButtonLg] for a
 * primary navigation target. [tint] defaults to the button's content colour (dimmed when disabled).
 * [contentDescription] is required: a button with no words is unusable
 * with a screen reader.
 */
@Composable
fun LettaIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = LettaControlTokens.iconButton,
    iconSize: Dp = LettaControlTokens.icon,
    tint: Color = Color.Unspecified,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
    ) {
        // Unspecified follows the button's own content colour, which dims when it is disabled.
        LettaIcon(imageVector, contentDescription, size = iconSize, tint = tint.takeOrElse { LocalContentColor.current })
    }
}
