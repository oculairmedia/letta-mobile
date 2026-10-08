package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_tool_awaiting_approval
import com.letta.mobile.sharedui.resources.rows_tool_done
import com.letta.mobile.sharedui.resources.rows_tool_outcome_failed
import com.letta.mobile.sharedui.resources.rows_tool_outcome_rejected
import com.letta.mobile.sharedui.resources.rows_tool_outcome_succeeded
import com.letta.mobile.sharedui.resources.rows_tool_outcome_warning
import com.letta.mobile.sharedui.resources.rows_tool_status_running
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.customColors
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * letta-mobile-bglj6.1.23: a tool call's state as the legacy Android cards showed it
 * (ChatToolCallCards' collapsed line; ProjectedToolTimeline's sheet rows): a status glyph at the
 * end of the line, an outcome row over the output, and a shimmering "Executing ..." while it runs.
 */

/** The glyph, its tint and its spoken name for a settled state; null while running or waiting. */
private class ToolOutcome(val icon: ImageVector, val tint: Color, val label: StringResource)

@Composable
private fun toolOutcome(state: ToolTimelineState): ToolOutcome? {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        ToolTimelineState.Succeeded -> ToolOutcome(LettaIcons.CheckCircle, scheme.primary, Res.string.rows_tool_outcome_succeeded)
        ToolTimelineState.Warning -> ToolOutcome(LettaIcons.Warning, warningTint(), Res.string.rows_tool_outcome_warning)
        ToolTimelineState.Failed -> ToolOutcome(LettaIcons.Error, scheme.error, Res.string.rows_tool_outcome_failed)
        ToolTimelineState.Rejected -> ToolOutcome(LettaIcons.Error, scheme.error, Res.string.rows_tool_outcome_rejected)
        ToolTimelineState.Running, ToolTimelineState.AwaitingApproval -> null
    }
}

/** The theme's warning ink, or tertiary where the host's theme sets none. */
@Composable
private fun warningTint(): Color {
    val warning = MaterialTheme.customColors.warningTextColor
    return if (warning == Color.Unspecified) MaterialTheme.colorScheme.tertiary else warning
}

/**
 * The glyph at the end of a tool's line: a turning refresh while it runs (still under reduced
 * motion), then a check, a warning or an error. Nothing while it waits on an approval.
 */
@Composable
internal fun ToolStatusGlyph(state: ToolTimelineState) {
    val size = Modifier.size(LettaDimens.Control.iconSm).testTag(ChatRowTestTags.TOOL_STATUS_GLYPH)
    if (state == ToolTimelineState.Running) {
        val angle = rememberSpinAngle(enabled = !LocalReducedMotion.current)
        Icon(
            imageVector = LettaIcons.Refresh,
            contentDescription = stringResource(Res.string.rows_tool_status_running),
            modifier = size.graphicsLayer { rotationZ = angle() },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val outcome = toolOutcome(state) ?: return
    Icon(imageVector = outcome.icon, contentDescription = stringResource(outcome.label), modifier = size, tint = outcome.tint)
}

/** One turn every [SPIN_MILLIS] (the legacy 1200 ms), or 0 when [enabled] is false. Read in the draw phase. */
@Composable
private fun rememberSpinAngle(enabled: Boolean): () -> Float {
    if (!enabled) return { 0f }
    val transition = rememberInfiniteTransition(label = "toolStatusSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(tween(SPIN_MILLIS, easing = LinearEasing)),
        label = "toolStatusSpinAngle",
    )
    return { angle }
}

/** The outcome over a settled tool's output: "Succeeded", "Failed", "Rejected" or "Warning", with its glyph. */
@Composable
internal fun ToolOutcomeLabel(state: ToolTimelineState) {
    val outcome = toolOutcome(state) ?: return
    val label = stringResource(outcome.label)
    Row(
        modifier = Modifier.testTag(ChatRowTestTags.TOOL_OUTCOME).semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Icon(imageVector = outcome.icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.iconSm), tint = outcome.tint)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = outcome.tint)
    }
}

/** The right-aligned status on an open tool row: its duration (or "Done"), "Failed", ...; none while running. */
@Composable
internal fun toolStatusLabel(state: ToolTimelineState, executionTimeMs: Long?): String? = when (state) {
    ToolTimelineState.Running -> null
    ToolTimelineState.AwaitingApproval -> stringResource(Res.string.rows_tool_awaiting_approval)
    ToolTimelineState.Succeeded -> if (executionTimeMs != null) formatRunDuration(executionTimeMs) else stringResource(Res.string.rows_tool_done)
    else -> toolOutcome(state)?.let { stringResource(it.label) }
}

/** The status label's ink: the error tint on a failure, the secondary one while waiting, else muted. */
@Composable
internal fun toolStatusColor(state: ToolTimelineState): Color = when (state) {
    ToolTimelineState.Failed, ToolTimelineState.Rejected -> MaterialTheme.colorScheme.error
    ToolTimelineState.Warning -> warningTint()
    ToolTimelineState.AwaitingApproval -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The legacy LiveStatusText: readable text with a soft highlight travelling across it while the
 * work is live; plain text at the muted alpha under reduced motion.
 */
@Composable
internal fun LiveStatusText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    if (LocalReducedMotion.current) {
        Text(text = text, style = style, color = base.copy(alpha = STATIC_ALPHA), modifier = modifier)
        return
    }
    val highlight = MaterialTheme.colorScheme.onSurface
    val transition = rememberInfiniteTransition(label = "liveStatusShimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "liveStatusShimmerProgress",
    )
    Text(
        text = text,
        style = style,
        color = base,
        modifier = modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithCache {
                val band = (size.width * SHIMMER_BAND_FRACTION).coerceAtLeast(SHIMMER_MIN_BAND.toPx())
                onDrawWithContent {
                    val center = progress * (size.width + 2f * band) - band
                    drawContent()
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(base, highlight, base),
                            start = Offset(center - band, 0f),
                            end = Offset(center + band, 0f),
                            tileMode = TileMode.Clamp,
                        ),
                        blendMode = BlendMode.SrcIn,
                    )
                }
            },
    )
}

private const val FULL_TURN = 360f
private const val SPIN_MILLIS = 1_200
private const val SHIMMER_MILLIS = 1_600
private const val SHIMMER_BAND_FRACTION = 0.4f
private val SHIMMER_MIN_BAND = 60.dp
private const val STATIC_ALPHA = 0.85f
