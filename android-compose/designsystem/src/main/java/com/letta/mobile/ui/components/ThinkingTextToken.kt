package com.letta.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

const val THINKING_TEXT_TOKEN_TEST_TAG = "thinking-text-token"

/**
 * letta-mobile-ndtc.3: gradient-text "thinking" indicator for the chat surface.
 *
 * Renders an ephemeral assistant-side text token ("Thinking…" or a delay
 * subtitle) with a horizontally-sweeping gradient applied to the glyphs. Sits
 * just above the composer, below the message list and any A2UI surfaces. Not
 * part of conversation history — purely transient UI state.
 *
 * Triggers (driven by `ChatUiState.isAgentTyping`, set in coordinators on
 * send / approval / A2UI follow-up) and deactivates on first streamed agent
 * frame, 60s timeout (which surfaces [delayMessage]), or user cancel.
 *
 * Reduced-motion: the sweep is suppressed (static gradient) when the system
 * accessibility flag is set or the caller passes `reducedMotion = true`.
 *
 * Themable colors: the gradient uses the active [ColorScheme]'s `primary` and
 * `tertiary` as the high-saturation stops, with `onSurfaceVariant` as the
 * faded tails. Themes retune by adjusting those scheme colors — no extra
 * thinking-specific tokens needed.
 */
@Composable
fun ThinkingTextToken(
    visible: Boolean,
    delayMessage: String? = null,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible || !delayMessage.isNullOrBlank(),
        enter = fadeIn(animationSpec = tween(durationMillis = 180)) +
            expandVertically(animationSpec = tween(durationMillis = 180)),
        exit = fadeOut(animationSpec = tween(durationMillis = 180)) +
            shrinkVertically(animationSpec = tween(durationMillis = 180)),
        modifier = modifier,
    ) {
        val scheme = MaterialTheme.colorScheme
        val text = delayMessage?.takeIf { it.isNotBlank() } ?: "Thinking…"

        val phase = if (reducedMotion) {
            0f
        } else {
            val transition = rememberInfiniteTransition(label = "thinking-text-token")
            val animated by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "thinking-text-token-phase",
            )
            animated
        }

        val brush = remember(phase, scheme) { thinkingTextTokenBrush(scheme, phase) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag(THINKING_TEXT_TOKEN_TEST_TAG),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(brush = brush),
            )
        }
    }
}

private const val SWEEP_BAND_PX = 800f
private const val SWEEP_TRAVEL_PX = 2_400f

/**
 * Horizontal sweep gradient sized so the bright band traverses the full text
 * over one animation cycle. Phase 0 starts the band well to the left of any
 * realistic text bounds; phase 1 leaves it well to the right.
 */
internal fun thinkingTextTokenBrush(scheme: ColorScheme, phase: Float): Brush {
    val tail = scheme.onSurfaceVariant.copy(alpha = 0.45f)
    val startX = -SWEEP_BAND_PX + phase * SWEEP_TRAVEL_PX
    val endX = startX + SWEEP_BAND_PX
    return Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to tail,
            0.35f to scheme.primary,
            0.5f to scheme.tertiary,
            0.65f to scheme.primary,
            1.0f to tail,
        ),
        start = Offset(startX, 0f),
        end = Offset(endX, 0f),
    )
}
