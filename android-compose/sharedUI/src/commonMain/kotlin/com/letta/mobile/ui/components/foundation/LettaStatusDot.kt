package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import com.letta.mobile.ui.theme.tokens.LettaStatusTone
import com.letta.mobile.ui.theme.tokens.LocalLettaCustomColors

/**
 * The one status dot: a filled circle of [LettaComponentDimens.statusDot] in [tone]'s colour. Replaces
 * the hand-built `Box(Modifier.size(8.dp).background(color, CircleShape))` dots, which coloured
 * "running" `primary` in some places and `tertiary` in others.
 *
 * Never colour alone: put words beside it, or use [LettaStatusLabel]. The dot itself is decorative
 * and has no semantics of its own.
 */
@Composable
fun LettaStatusDot(tone: LettaStatusTone, modifier: Modifier = Modifier) {
    LettaStatusDot(color = tone.color(), modifier = modifier)
}

/**
 * [LettaStatusDot] in an explicit [color], for a status whose colour is data (an agent's own colour, a
 * category). Prefer the [LettaStatusTone] overload for a state.
 */
@Composable
fun LettaStatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(LettaComponentDimens.statusDot).background(color, CircleShape))
}

/** The one colour of a status tone, shared by [LettaStatusDot] and [LettaStatusLabel]. */
@Composable
@ReadOnlyComposable
fun LettaStatusTone.color(): Color = when (this) {
    LettaStatusTone.Idle -> MaterialTheme.colorScheme.outline
    LettaStatusTone.Running -> MaterialTheme.colorScheme.primary
    LettaStatusTone.Success -> LocalLettaCustomColors.current.successColor
    LettaStatusTone.Warning -> LocalLettaCustomColors.current.let { colors ->
        colors.warningTextColor.takeIf { it != Color.Unspecified } ?: colors.reconnectingColor
    }
    LettaStatusTone.Error -> MaterialTheme.colorScheme.error
}

/**
 * A status in words after a [LettaStatusDot], or after [icon] when the tone has a glyph, all in
 * [color] and with no container ("✓ Connected", "• Syncing").
 */
@Composable
fun LettaStatusLabel(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.xs),
    ) {
        if (icon != null) {
            LettaIcon(icon, contentDescription = null, style = LettaIconStyle(LettaControlTokens.iconSm, color))
        } else {
            LettaStatusDot(color)
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** [LettaStatusLabel] in [tone]'s colour. */
@Composable
fun LettaStatusLabel(text: String, tone: LettaStatusTone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    LettaStatusLabel(text = text, color = tone.color(), modifier = modifier, icon = icon)
}
