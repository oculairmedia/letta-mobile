package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.tokens.LettaControlTokens

/**
 * How a [LettaIcon] draws: its [size] on the scale ([LettaControlTokens.icon] by default,
 * [LettaControlTokens.iconSm] for a glyph inside a small control) and its [tint]
 * ([Color.Unspecified] follows the content colour where it is drawn).
 */
@Immutable
data class LettaIconStyle(
    val size: Dp = LettaControlTokens.icon,
    val tint: Color = Color.Unspecified,
)

/**
 * The one way to draw a glyph: a Material [Icon] at a size from the scale. Replaces raw `Icon(` calls
 * with their own `Modifier.size(...)` (18, 13, and Material's bare 24 dp all coexisted).
 *
 * Take [imageVector] from `LettaIcons`, not `Lucide.*` or Material `Icons.*`, so one concept has one
 * glyph. [style] sets the size and tint. [contentDescription] is null when the glyph only decorates
 * words beside it.
 */
@Composable
fun LettaIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    style: LettaIconStyle = LettaIconStyle(),
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.size(style.size),
        tint = style.tint.takeOrElse { LocalContentColor.current },
    )
}

/**
 * What a [LettaIconButton] shows: [imageVector] with its [contentDescription] (required: a button with
 * no words is unusable with a screen reader), drawn at [size] ([LettaControlTokens.iconButton] by
 * default; [LettaControlTokens.iconButtonSm] for a glyph inside another control such as a tab's close
 * cross, [LettaControlTokens.iconButtonLg] for a primary navigation target) around a glyph in [glyph].
 * The glyph's tint defaults to the button's content colour, which dims when it is disabled.
 */
@Immutable
data class LettaButtonIcon(
    val imageVector: ImageVector,
    val contentDescription: String,
    val size: Dp = LettaControlTokens.iconButton,
    val glyph: LettaIconStyle = LettaIconStyle(),
)

/**
 * The one icon button: a Material [IconButton] laid out and drawn at [LettaButtonIcon.size] around a
 * [LettaIcon]. Its hit area is never smaller than the platform's minimum touch target (48 dp): Compose
 * extends a smaller clickable's touch bounds to it, so a compact button stays easy to hit without
 * pushing its neighbours apart. Replaces `IconButton` calls sized 48, 28, 20 dp and eight other sizes.
 */
@Composable
fun LettaIconButton(
    icon: LettaButtonIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(icon.size)) {
        LettaIcon(icon.imageVector, icon.contentDescription, style = icon.glyph)
    }
}

/**
 * A [LettaIconButton] whose glyph the caller draws, for a glyph that changes in place (a copy's check).
 * The glyph only decorates: the caller names the button through [modifier]'s semantics.
 */
@Composable
internal fun LettaGlyphButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = LettaControlTokens.iconButton,
    glyph: @Composable () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier.size(size), content = glyph)
}
