package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.timeline_scroll_to_latest
import com.letta.mobile.sharedui.resources.timeline_running_tool
import com.letta.mobile.sharedui.resources.timeline_thinking
import com.letta.mobile.sharedui.resources.timeline_thinking_elapsed
import com.letta.mobile.sharedui.resources.timeline_today
import com.letta.mobile.sharedui.resources.timeline_yesterday
import com.letta.mobile.data.chat.projection.parseTimestampEpochMillis
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.surface.timeline.rows.formatElapsedClock
import com.letta.mobile.ui.chat.surface.timeline.rows.rememberElapsedSeconds
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.ChatTimelineDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** letta-mobile-bglj6.1: test tags for the timeline's chrome. */
internal object ChatTimelineTags {
    const val LIST = "chat-timeline-list"
    const val SKELETON = "chat-timeline-skeleton"
    const val STATUS = "chat-timeline-status"
    const val WELCOME = "chat-timeline-welcome"
    const val SCROLL_TO_LATEST = "chat-timeline-scroll-to-latest"
    const val THINKING = "chat-timeline-thinking"
    const val PINNED_PROMPT = "chat-timeline-pinned-prompt"
    const val A2UI_STACK = "chat-timeline-a2ui"
    const val GOAL = "chat-timeline-goal"
    const val FONT_SCALE = "chat-timeline-font-scale"
}

/** The two edge-fade strengths, already animated. */
@Immutable
internal data class TimelineFadeAlphas(val top: Float, val bottom: Float)

/**
 * Edge fades for a REVERSED list: "can scroll forward" is toward older rows (the top) and "can
 * scroll backward" toward the newest (the bottom). The top fade stands down while a prompt is
 * pinned there: the opaque card already terminates the content (desktop rememberChatListFadeAlphas).
 */
@Composable
internal fun rememberTimelineFadeAlphas(
    canScrollTowardOlder: Boolean,
    canScrollTowardNewer: Boolean,
    promptPinned: Boolean,
): TimelineFadeAlphas {
    val top by animateFloatAsState(
        targetValue = if (canScrollTowardOlder && !promptPinned) 1f else 0f,
        animationSpec = tween(ChatTimelineDimens.fadeAnimationMillis),
        label = "timelineTopFade",
    )
    val bottom by animateFloatAsState(
        targetValue = if (canScrollTowardNewer) 1f else 0f,
        animationSpec = tween(ChatTimelineDimens.fadeAnimationMillis),
        label = "timelineBottomFade",
    )
    return TimelineFadeAlphas(top, bottom)
}

/**
 * Dissolves the top and bottom of the content to transparent with a DstIn gradient mask, so the
 * list grades into the surrounding chrome instead of hard-clipping. Lifted from desktop's
 * fadingEdges (Android's chatFadingEdges is the same idea). No-op when both alphas are 0.
 */
internal fun Modifier.timelineFadingEdges(
    alphas: TimelineFadeAlphas,
    topLength: Dp = ChatTimelineDimens.topFadeLength,
    bottomLength: Dp = ChatTimelineDimens.bottomFadeLength,
): Modifier {
    if (alphas.top <= 0f && alphas.bottom <= 0f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawFadeBand(topLength.toPx(), alphas.top, fromTop = true)
            drawFadeBand(bottomLength.toPx(), alphas.bottom, fromTop = false)
        }
}

private fun DrawScope.drawFadeBand(lengthPx: Float, alpha: Float, fromTop: Boolean) {
    val band = lengthPx.coerceAtMost(size.height / 2f)
    if (alpha <= 0f || band <= 0f) return
    val startY = if (fromTop) 0f else size.height - band
    val faded = Color.Black.copy(alpha = 1f - alpha)
    drawRect(
        brush = Brush.verticalGradient(
            colors = if (fromTop) listOf(faded, Color.Black) else listOf(Color.Black, faded),
            startY = startY,
            endY = startY + band,
        ),
        topLeft = Offset(0f, startY),
        size = Size(size.width, band),
        blendMode = BlendMode.DstIn,
    )
}

/** A quiet squircle, not a FAB: it sits over the reading area (desktop ScrollToLatestButton). */
@Composable
internal fun ScrollToLatestButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(LettaDimens.Control.iconButton).testTag(ChatTimelineTags.SCROLL_TO_LATEST),
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = LettaIcons.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.timeline_scroll_to_latest),
                modifier = Modifier.size(LettaDimens.Control.icon),
            )
        }
    }
}

/**
 * Trails the thread while the agent is working and nothing has streamed yet: the Android
 * thinking token (designsystem ThinkingTextToken as ChatScreen feeds it), "0:12  Thinking…" or
 * "0:12  Running Bash", its glyphs swept by a primary/tertiary gradient.
 */
@Composable
internal fun ThinkingRow(messages: List<UiMessage>, modifier: Modifier = Modifier) {
    val activity = remember(messages) { activeRunActivity(messages) }
    val elapsed by rememberElapsedSeconds(active = true, startedAtEpochMs = activity.startedAtEpochMs)
    val phase = activity.runningToolName?.let { stringResource(Res.string.timeline_running_tool, it) }
        ?: stringResource(Res.string.timeline_thinking)
    val sweep = rememberThinkingSweep()
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .heightIn(min = ChatRowSpacing.thinkingRowHeight)
            .padding(vertical = LettaDimens.Space.xs)
            .testTag(ChatTimelineTags.THINKING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.timeline_thinking_elapsed, formatElapsedClock(elapsed), phase),
            style = MaterialTheme.typography.bodyMedium,
            // Drawn solid, then swept by the gradient in the draw phase (thinkingSweep).
            color = scheme.onSurface,
            modifier = Modifier
                .defaultMinSize(minHeight = ChatRowSpacing.thinkingTextMinHeight)
                .thinkingSweep(scheme, sweep),
        )
    }
}

/** What the newest run is doing (feature-chat activeRunActivity): its running tool and start. */
private class ActiveRunActivity(val runningToolName: String?, val startedAtEpochMs: Long?)

private fun activeRunActivity(messages: List<UiMessage>): ActiveRunActivity {
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
 * The thinking token's sweep phase, 0..1, read only where it is drawn: the row never recomposes
 * for it. Still (0) under reduced motion.
 */
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

/**
 * Sweeps the drawn text with a bright band crossing it (the Android thinking token's gradient):
 * the glyphs are drawn, then the gradient is kept only where they are (SrcIn in an offscreen
 * layer). The band is sized in dp, so it crosses the same share of text at every density.
 */
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

/** How many band widths the band travels per sweep, from just off the start. */
private const val THINKING_SWEEP_TRAVEL_BANDS = 3f
private const val THINKING_STOP_INNER = 0.35f
private const val THINKING_STOP_MID = 0.5f
private const val THINKING_STOP_OUTER = 0.65f

/** A day boundary as the Android timeline marks it (designsystem DateSeparator): a small centred pill. */
@Composable
internal fun DayDividerRow(date: LocalDate, today: LocalDate, modifier: Modifier = Modifier) {
    val label = when (val day = dayLabelOf(date, today)) {
        DayLabel.Today -> stringResource(Res.string.timeline_today)
        DayLabel.Yesterday -> stringResource(Res.string.timeline_yesterday)
        is DayLabel.Date -> day.text
    }
    Box(
        modifier = modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth().padding(vertical = LettaDimens.Space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(percent = PILL_PERCENT)) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val PILL_PERCENT = 50
