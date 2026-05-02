package com.letta.mobile.ui.screens.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.icons.LettaIcons

/**
 * Width of the timeline gutter on the left of a run block. Sized to fit a
 * dot icon plus a small breathing margin; the vertical line passes through
 * the centre of the gutter.
 */
private val RunGutterWidth = 24.dp

/** Diameter of the per-step indicator dot painted in the gutter. */
private val StepDotSize = 10.dp

/** Stroke width of the vertical timeline rule. */
private val GutterLineWidth = 2.dp

/**
 * Renders a contiguous run of assistant messages sharing a `runId` as a
 * single grouped block with a timeline gutter on the left. The gutter holds
 * one [StepDotIcon]-classified dot per message and a vertical line that
 * connects them.
 *
 * Collapsing the run hides every step except the last one. The last step
 * stays visible so the user can see the run's final outcome at a glance and
 * still expand for full detail.
 *
 * @param messages messages in **chat order** (oldest first within the run).
 *        The render order matches.
 * @param collapsed when true, only the last (most recent) message renders
 *        in the gutter, with a "+N more" affordance.
 * @param onToggleCollapsed click handler for the run header chevron.
 * @param renderRow factory that renders one message inside the run with
 *        the supplied [GroupPosition] and a row-level [Modifier] that the
 *        caller should apply to its bubble container so the gutter aligns.
 *
 * letta-mobile-m772.2 / m772.3 / m772.4 (collapse) / m772.9 (gutter centring)
 * / m772.10 (single-message short circuit handled at grouping layer).
 */
@Composable
fun RunBlock(
    messages: List<UiMessage>,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier,
    renderRow: @Composable (
        message: UiMessage,
        position: GroupPosition,
        rowModifier: Modifier,
    ) -> Unit,
) {
    if (messages.isEmpty()) return

    // Defensive: the grouping layer already guarantees ≥2 messages for a
    // RunBlock, but if we ever get a single-message run (e.g. via a future
    // caller), short-circuit to a plain row so we don't paint a degenerate
    // 1-dot gutter. letta-mobile-m772.10.
    if (messages.size == 1) {
        renderRow(messages.single(), GroupPosition.None, Modifier.fillMaxWidth())
        return
    }

    val gutterColor = MaterialTheme.colorScheme.outlineVariant
    val hiddenCount = if (collapsed) messages.size - 1 else 0

    // letta-mobile-d2z6 follow-up: kept the outer animateContentSize so
    // stream-end (final message addition) settles smoothly instead of
    // popping. Removing it caused a visible duplicate-flash as the prior
    // tail's GroupPosition flipped from Last → Middle when the new tail
    // arrived. The inner AnimatedVisibility was removed (we render the
    // visible set uniformly now) so the previous animation stack is no
    // longer in play.
    //
    // letta-mobile-5e0f.r2: suppress animateContentSize during pinch so
    // we don't get cascading 150ms height interpolations across many
    // bubbles per pinch frame. The animation is still useful for its
    // intended trigger (stream-end / new-tail arrival), and that trigger
    // is impossible during a pinch.
    val isPinching = com.letta.mobile.ui.theme.LocalChatIsPinching.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isPinching) Modifier else Modifier.animateContentSize()),
    ) {
        RunHeader(
            messageCount = messages.size,
            collapsed = collapsed,
            onToggleCollapsed = onToggleCollapsed,
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            // Timeline gutter — drawn behind the rows so the vertical rule
            // passes through every dot. Sized via the same Column so its
            // height tracks the rendered messages exactly.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 0.dp),
            ) {
                // letta-mobile-d2z6: render *all* messages inside a single
                // expand/collapse container. Previously the tail (last
                // message) was drawn outside the AnimatedVisibility so it
                // remained visible when collapsed. That arrangement caused
                // a structural swap mid-stream: when a new sibling landed,
                // the previous tail jumped from the always-visible block
                // into the AnimatedVisibility block, triggering a fresh
                // expandVertically animation and the visible bubble
                // movement Emmanuel reported. Treating "the visible set"
                // uniformly removes that swap entirely — when collapsed we
                // simply render only `messages.last()`; when expanded we
                // render the whole run.
                val visibleMessages = if (collapsed) {
                    listOf(messages.last())
                } else {
                    messages
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    visibleMessages.forEachIndexed { idx, msg ->
                        val pos = when {
                            collapsed -> GroupPosition.None
                            visibleMessages.size == 1 -> GroupPosition.None
                            idx == 0 -> GroupPosition.First
                            idx == visibleMessages.lastIndex -> GroupPosition.Last
                            else -> GroupPosition.Middle
                        }
                        val drawLineAbove = !collapsed && idx > 0
                        val drawLineBelow = !collapsed && idx < visibleMessages.lastIndex
                        RunStepRow(
                            message = msg,
                            position = pos,
                            gutterColor = gutterColor,
                            drawLineAbove = drawLineAbove,
                            drawLineBelow = drawLineBelow,
                            renderRow = renderRow,
                            collapsedHiddenCount = if (collapsed && idx == visibleMessages.lastIndex) hiddenCount else 0,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Header row: chevron + step count summary. Click toggles collapse.
 */
@Composable
private fun RunHeader(
    messageCount: Int,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
) {
    val label = if (collapsed) {
        "Run · $messageCount steps · tap to expand"
    } else {
        "Run · $messageCount steps · tap to collapse"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (collapsed) "Expand run" else "Collapse run",
            ) { onToggleCollapsed() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = LettaIcons.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier
                .size(16.dp)
                .rotate(if (collapsed) 0f else 180f),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One step row: gutter on the left (dot + connector segments) and the
 * caller-supplied bubble on the right.
 */
@Composable
private fun RunStepRow(
    message: UiMessage,
    position: GroupPosition,
    gutterColor: androidx.compose.ui.graphics.Color,
    drawLineAbove: Boolean,
    drawLineBelow: Boolean,
    renderRow: @Composable (
        message: UiMessage,
        position: GroupPosition,
        rowModifier: Modifier,
    ) -> Unit,
    collapsedHiddenCount: Int = 0,
) {
    val dotColor = message.runStepDotColor()
    val icon = remember(message.id, message.role, message.isReasoning, message.toolCalls, message.approvalRequest) {
        message.runStepDotIcon()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // Gutter column: a fixed-width box that draws the connector lines
        // and the dot. We keep this layout-stable so successive rows align
        // pixel-perfect (letta-mobile-m772.9: dots centred on the gutter axis,
        // lines on the same axis so there's no horizontal jitter).
        Box(
            modifier = Modifier
                .width(RunGutterWidth)
                .drawBehind {
                    val cx = size.width / 2f
                    val stroke = GutterLineWidth.toPx()
                    if (drawLineAbove) {
                        drawLine(
                            color = gutterColor,
                            start = Offset(cx, 0f),
                            end = Offset(cx, size.height / 2f),
                            strokeWidth = stroke,
                        )
                    }
                    if (drawLineBelow) {
                        drawLine(
                            color = gutterColor,
                            start = Offset(cx, size.height / 2f),
                            end = Offset(cx, size.height),
                            strokeWidth = stroke,
                        )
                    }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .size(StepDotSize)
                        .background(dotColor, CircleShape),
                )
                if (collapsedHiddenCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "+$collapsedHiddenCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Touch the icon classification value so the IDE/compiler
                // sees the dependency chain (and so a future visual upgrade
                // can swap the dot for an actual icon trivially).
                @Suppress("UNUSED_EXPRESSION") icon
            }
        }

        // Right-hand bubble. Caller decides padding inside the row; we just
        // hand them a Modifier that fills the remaining width.
        renderRow(message, position, Modifier.fillMaxWidth())
    }
}
