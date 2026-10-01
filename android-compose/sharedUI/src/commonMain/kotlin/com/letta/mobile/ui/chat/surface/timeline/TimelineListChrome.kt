package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.letta.mobile.sharedui.resources.timeline_thinking
import com.letta.mobile.sharedui.resources.timeline_today
import com.letta.mobile.sharedui.resources.timeline_yesterday
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatTimelineDimens
import com.letta.mobile.ui.theme.LettaDimens
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

/** Trails the thread while the agent is working and nothing has streamed yet. */
@Composable
internal fun ThinkingRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .padding(vertical = LettaDimens.Space.xs)
            .testTag(ChatTimelineTags.THINKING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(LettaDimens.Control.iconSm),
            strokeWidth = LettaDimens.Space.hair,
        )
        Text(
            text = stringResource(Res.string.timeline_thinking),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A day boundary: the rules carry the boundary and the word only names it (desktop). */
@Composable
internal fun DayDividerRow(date: LocalDate, today: LocalDate, modifier: Modifier = Modifier) {
    val ruleColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)
    val label = when (val day = dayLabelOf(date, today)) {
        DayLabel.Today -> stringResource(Res.string.timeline_today)
        DayLabel.Yesterday -> stringResource(Res.string.timeline_yesterday)
        is DayLabel.Date -> day.text
    }
    Row(
        modifier = modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Box(Modifier.weight(1f).height(LettaDimens.Stroke.hairline).background(ruleColor))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ChatTimelineDimens.Alpha.mutedLabel),
            textAlign = TextAlign.Center,
        )
        Box(Modifier.weight(1f).height(LettaDimens.Stroke.hairline).background(ruleColor))
    }
}
