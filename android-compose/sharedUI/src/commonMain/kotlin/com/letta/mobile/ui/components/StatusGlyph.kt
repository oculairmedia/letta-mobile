package com.letta.mobile.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.customColors

/**
 * The one status glyph: a spinner while working, then a check, a warning, an error, an info mark or
 * an eye, by [tone]. In a fixed slot of [size] (the line glyph's by default), cross-fading as the tone
 * changes, so a line settling never moves its label. Decorative unless [contentDescription] names the
 * state. Every line, card and panel that says a status draws it through here.
 */
@Composable
internal fun StatusGlyph(
    tone: StatusTone,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = ChatRowSpacing.runGlyphSize,
) {
    val reducedMotion = LocalReducedMotion.current
    val described = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Crossfade(
        targetState = tone,
        animationSpec = if (reducedMotion) snap() else tween(STATUS_GLYPH_FADE_MILLIS),
        modifier = modifier.size(size).then(described),
        label = "statusGlyph",
    ) { shown ->
        val glyph = Modifier.size(size)
        if (shown == StatusTone.Working) {
            WorkingGlyph(reducedMotion || !LocalStatusGlyphAnimated.current, glyph)
        } else {
            Icon(imageVector = shown.icon(), contentDescription = null, modifier = glyph, tint = shown.tint())
        }
    }
}

/**
 * Whether the working glyph spins. A host whose own ambient glow already says "working" (a docked
 * panel) turns it off; under reduced motion it is a still ring whatever this says.
 */
internal val LocalStatusGlyphAnimated = staticCompositionLocalOf { true }

/** A small spinner while the work runs; a still ring when [still]. */
@Composable
private fun WorkingGlyph(still: Boolean, modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.tertiary
    if (still) {
        Box(modifier.border(ChatRowSpacing.runGlyphStroke, tint, CircleShape))
    } else {
        CircularProgressIndicator(modifier = modifier, color = tint, strokeWidth = ChatRowSpacing.runGlyphStroke)
    }
}

private fun StatusTone.icon(): ImageVector = when (this) {
    StatusTone.Attention -> LettaIcons.Warning
    StatusTone.Failed -> LettaIcons.Error
    StatusTone.Info -> LettaIcons.Info
    StatusTone.Watching -> LettaIcons.Visibility
    StatusTone.Working, StatusTone.Done, StatusTone.Success -> LettaIcons.Check
}

@Composable
private fun StatusTone.tint(): Color = when (this) {
    StatusTone.Attention -> MaterialTheme.colorScheme.secondary
    StatusTone.Failed -> MaterialTheme.colorScheme.error
    StatusTone.Success -> successTint()
    StatusTone.Working -> MaterialTheme.colorScheme.tertiary
    StatusTone.Done, StatusTone.Info, StatusTone.Watching -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The theme's success ink, or primary where the host's theme sets none. */
@Composable
internal fun successTint(): Color {
    return MaterialTheme.customColors.successColor.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary
}

private const val STATUS_GLYPH_FADE_MILLIS = 150
