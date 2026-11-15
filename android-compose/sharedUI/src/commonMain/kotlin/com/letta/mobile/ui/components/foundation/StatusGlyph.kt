package com.letta.mobile.ui.components.foundation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LocalLettaCustomColors
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion

/**
 * The one status glyph: a spinner while working, then a check, a warning, an error, an info mark or
 * an eye, by [tone]; in a list of tasks an empty ring (pending), a clock (under way), a ringed check
 * (completed) or a bare check (chosen). In a fixed slot of [size] (the line glyph's, 13 dp, by
 * default; a task list passes the standard glyph size), cross-fading as the tone changes, so a line
 * settling never moves its label. Decorative unless [contentDescription] names the state. Every
 * line, card, sheet and panel that says a status draws it through here; a status dot is
 * [LettaStatusDot].
 */
@Composable
internal fun StatusGlyph(
    tone: StatusTone,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = LettaControlTokens.iconSm,
) {
    val reducedMotion = LocalLettaReducedMotion.current
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
        Box(modifier.border(LettaComponentDimens.statusGlyphStroke, tint, CircleShape))
    } else {
        CircularProgressIndicator(modifier = modifier, color = tint, strokeWidth = LettaComponentDimens.statusGlyphStroke)
    }
}

private fun StatusTone.icon(): ImageVector = when (this) {
    StatusTone.Attention -> LettaIcons.Warning
    StatusTone.Failed -> LettaIcons.Error
    StatusTone.Info -> LettaIcons.Info
    StatusTone.Watching -> LettaIcons.Visibility
    StatusTone.Pending -> LettaIcons.Circle
    StatusTone.Underway -> LettaIcons.AccessTime
    StatusTone.Completed -> LettaIcons.CheckCircle
    StatusTone.Working, StatusTone.Done, StatusTone.Success, StatusTone.Chosen -> LettaIcons.Check
}

@Composable
private fun StatusTone.tint(): Color = when (this) {
    StatusTone.Attention -> MaterialTheme.colorScheme.secondary
    StatusTone.Failed -> MaterialTheme.colorScheme.error
    StatusTone.Success, StatusTone.Completed -> successTint()
    StatusTone.Working -> MaterialTheme.colorScheme.tertiary
    StatusTone.Underway -> MaterialTheme.colorScheme.primary
    StatusTone.Chosen -> LocalContentColor.current
    StatusTone.Done, StatusTone.Info, StatusTone.Watching, StatusTone.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The theme's success ink, or primary where the host's theme sets none. */
@Composable
internal fun successTint(): Color {
    return LocalLettaCustomColors.current.successColor.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary
}

private const val STATUS_GLYPH_FADE_MILLIS = 150
