package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.letta.mobile.data.chat.projection.parseTimestampEpochMillis
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.timeline_running_tool
import com.letta.mobile.sharedui.resources.timeline_thinking
import com.letta.mobile.sharedui.resources.timeline_thinking_elapsed
import com.letta.mobile.ui.chat.surface.timeline.rows.formatElapsedClock
import com.letta.mobile.ui.chat.surface.timeline.rows.rememberElapsedSeconds
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.ChatTimelineDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1.21: what the newest run is doing right now — the same strings the
 * timeline thinking row, the Touch companion, and the canvas head popup share
 * ("0:12  Thinking…" / "0:12  Running Bash").
 */
internal data class ActiveRunActivity(
    val runningToolName: String?,
    val startedAtEpochMs: Long?,
)

internal fun activeRunActivity(messages: List<UiMessage>): ActiveRunActivity {
    val newestRunId = messages.lastOrNull()?.runId
    val run = messages.takeLastWhile { it.runId != null && it.runId == newestRunId }
    return ActiveRunActivity(
        runningToolName = run.flatMap { it.toolCalls.orEmpty() }
            .lastOrNull { it.status.isNullOrBlank() || it.status.equals(RUNNING_STATUS, ignoreCase = true) }
            ?.name,
        startedAtEpochMs = run.firstNotNullOfOrNull { parseTimestampEpochMillis(it.timestamp) },
    )
}

private const val RUNNING_STATUS = "running"

/**
 * The thinking token's glyphs, swept by a primary/tertiary gradient. Callers own the row chrome
 * (timeline vs companion).
 */
@Composable
internal fun ThinkingStatusText(
    messages: List<UiMessage>,
    modifier: Modifier = Modifier,
    delayMessage: String? = null,
) {
    val activity = remember(messages) { activeRunActivity(messages) }
    val elapsed by rememberElapsedSeconds(active = true, startedAtEpochMs = activity.startedAtEpochMs)
    val phase = activity.runningToolName?.let { stringResource(Res.string.timeline_running_tool, it) }
        ?: delayMessage?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.timeline_thinking)
    val sweep = rememberThinkingSweep()
    val scheme = MaterialTheme.colorScheme
    Text(
        text = stringResource(Res.string.timeline_thinking_elapsed, formatElapsedClock(elapsed), phase),
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurface,
        modifier = modifier
            .defaultMinSize(minHeight = ChatRowSpacing.thinkingTextMinHeight)
            .thinkingSweep(scheme, sweep),
    )
}

@Composable
private fun rememberThinkingSweep(): () -> Float {
    if (LocalReducedMotion.current) return { 0f }
    val transition = rememberInfiniteTransition(label = "thinkingSweep")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(THINKING_SWEEP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "thinkingSweepPhase",
    )
    return { phase.value }
}

private fun Modifier.thinkingSweep(scheme: ColorScheme, phase: () -> Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val band = ChatTimelineDimens.thinkingSweepBand.toPx()
        val tail = scheme.onSurfaceVariant.copy(alpha = ChatRowAlpha.thinkingTail)
        val stops = arrayOf(
            0f to tail,
            THINKING_STOP_INNER to scheme.primary,
            THINKING_STOP_MID to scheme.tertiary,
            THINKING_STOP_OUTER to scheme.primary,
            1f to tail,
        )
        onDrawWithContent {
            drawContent()
            val startX = -band + phase() * band * THINKING_SWEEP_TRAVEL_BANDS
            drawRect(
                brush = Brush.linearGradient(colorStops = stops, start = Offset(startX, 0f), end = Offset(startX + band, 0f)),
                blendMode = BlendMode.SrcIn,
            )
        }
    }

private const val THINKING_SWEEP_MILLIS = 2_400
private const val THINKING_SWEEP_TRAVEL_BANDS = 3f
private const val THINKING_STOP_INNER = 0.35f
private const val THINKING_STOP_MID = 0.5f
private const val THINKING_STOP_OUTER = 0.65f
