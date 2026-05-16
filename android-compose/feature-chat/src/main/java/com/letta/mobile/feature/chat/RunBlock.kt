package com.letta.mobile.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.icons.LettaIcons

/**
 * Width of the timeline gutter on the left of a run block. Sized to fit a
 * dot icon plus a small breathing margin; the vertical line passes through
 * the centre of the gutter.
 */
private val RunGutterWidth = 12.dp

/** Diameter of the per-step indicator dot painted in the gutter. */
private val StepDotSize = 3.dp

/** Stroke width of the run identity rule drawn through the step dots. */
private val RunIdentityLineWidth = 1.dp

/** Dot pattern for the run identity rule so it reads as a guide, not a border. */
private val RunIdentityDotLength = 1.dp
private val RunIdentityDotGap = 4.dp

/** Center offset for step dots on regular assistant/reasoning rows. */
private val DefaultStepDotCenterY = 17.dp

/**
 * Tool-call rows render directly as a tool card, whose first meaningful text
 * row sits lower than plain assistant/reasoning content. Anchor the bead to
 * that card header instead of the generic text row.
 */
private val ToolCallStepDotCenterY = 25.5f.dp

/**
 * Compact grouped tool-call cards render directly in the run row instead of
 * through the normal chat-message wrapper, so their first text line sits much
 * closer to the top. Keep this dot on that first-line midline.
 */
private val CompactToolCallGroupStepDotCenterY = 18.dp

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
internal fun RunBlock(
    messages: List<UiMessage>,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    activeApprovalRequestId: String? = null,
    onApprovalDecision: ((String, List<String>, Boolean, String?) -> Unit)? = null,
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

    val runIdentityColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.24f)

    // Keep the run container height static from Compose's perspective. Lazy
    // timeline recycling and manual tool-output expansion must not replay run
    // entrance motion or animate the entire block around the user's scroll
    // position.
    Column(
        modifier = modifier
            .fillMaxWidth(),
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
                //
                // Motion restoration: wrap the visible-step Column in
                // AnimatedContent keyed on `collapsed` only (NOT on
                // `messages`), so user-driven expand/collapse plays the
                // canonical ChatMotion ramp while streaming updates flow
                // through the inner lambda without re-triggering the
                // transition. Mirrors the pattern in ToolOutputRenderer
                // (single source of truth for expand/collapse motion).
                AnimatedContent(
                    targetState = collapsed,
                    transitionSpec = {
                        (ChatMotion.expandEnter() togetherWith ChatMotion.expandExit())
                            .using(SizeTransform(clip = true) { _, _ -> ChatMotion.contentSizeSpec })
                    },
                    label = "RunBlockExpandCollapse",
                ) { isCollapsed ->
                    val visibleMessages = if (isCollapsed) {
                        listOf(selectCollapsedPreview(messages))
                    } else {
                        messages
                    }
                    val visibleSteps = remember(visibleMessages) {
                        compactRunToolCallSteps(visibleMessages)
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        visibleSteps.forEachIndexed { idx, step ->
                            key(step.key) {
                                val position = when {
                                    isCollapsed -> GroupPosition.None
                                    visibleSteps.size == 1 -> GroupPosition.None
                                    idx == 0 -> GroupPosition.First
                                    idx == visibleSteps.lastIndex -> GroupPosition.Last
                                    else -> GroupPosition.Middle
                                }
                                val drawLineAbove = idx > 0
                                val drawLineBelow = idx < visibleSteps.lastIndex
                                when (step) {
                                    is RunTimelineStep.Message -> RunMessageStepRow(
                                        message = step.message,
                                        position = position,
                                        runIdentityColor = runIdentityColor,
                                        drawLineAbove = drawLineAbove,
                                        drawLineBelow = drawLineBelow,
                                        renderRow = renderRow,
                                    )

                                    is RunTimelineStep.ToolCallGroup -> RunToolCallGroupStepRow(
                                        step = step,
                                        runIdentityColor = runIdentityColor,
                                        drawLineAbove = drawLineAbove,
                                        drawLineBelow = drawLineBelow,
                                        animateRows = isStreaming,
                                        activeApprovalRequestId = activeApprovalRequestId,
                                        onApprovalDecision = onApprovalDecision,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal sealed interface RunTimelineStep {
    val key: String

    data class Message(
        val message: UiMessage,
    ) : RunTimelineStep {
        override val key: String = message.id
    }

    data class ToolCallGroup(
        val messages: List<UiMessage>,
        val toolCalls: List<UiToolCall>,
        val pendingApprovalToolCallIds: Set<String>,
        val approvalRequests: List<UiApprovalRequest>,
    ) : RunTimelineStep {
        override val key: String = "tool-group-${messages.first().id}"
    }
}

internal fun compactRunToolCallSteps(messages: List<UiMessage>): List<RunTimelineStep> {
    if (messages.isEmpty()) return emptyList()
    val steps = ArrayList<RunTimelineStep>(messages.size)
    val pendingToolMessages = ArrayList<UiMessage>()

    fun flushToolMessages() {
        when (pendingToolMessages.size) {
            0 -> Unit
            1 -> steps.add(RunTimelineStep.Message(pendingToolMessages.single()))
            else -> {
                val groupedMessages = pendingToolMessages.toList()
                steps.add(
                    RunTimelineStep.ToolCallGroup(
                        messages = groupedMessages,
                        toolCalls = groupedMessages.flatMap { it.toolCalls.orEmpty() },
                        pendingApprovalToolCallIds = groupedMessages
                            .flatMap { message -> message.approvalRequest?.toolCalls.orEmpty() }
                            .mapTo(mutableSetOf()) { it.toolCallId },
                        approvalRequests = groupedMessages
                            .mapNotNull { it.approvalRequest }
                            .distinctBy { it.requestId },
                    )
                )
            }
        }
        pendingToolMessages.clear()
    }

    messages.forEach { message ->
        when {
            message.isRunCompactableToolCallMessage() -> {
                pendingToolMessages += message
            }
            message.hasStandaloneContentAndToolCalls() -> {
                flushToolMessages()
                steps.add(RunTimelineStep.Message(message.withoutToolCallsForStandaloneContent()))
                pendingToolMessages += message.withoutStandaloneContentForToolGroup()
            }
            else -> {
                flushToolMessages()
                steps.add(RunTimelineStep.Message(message))
            }
        }
    }
    flushToolMessages()
    return steps
}

private fun UiMessage.isRunCompactableToolCallMessage(): Boolean =
    role == "assistant" &&
        !isReasoning &&
        !isError &&
        content.isBlank() &&
        generatedUi == null &&
        approvalResponse == null &&
        attachments.isEmpty() &&
        !toolCalls.isNullOrEmpty()

private fun UiMessage.hasStandaloneContentAndToolCalls(): Boolean =
    role == "assistant" &&
        !isReasoning &&
        !isError &&
        content.isNotBlank() &&
        generatedUi == null &&
        approvalResponse == null &&
        attachments.isEmpty() &&
        !toolCalls.isNullOrEmpty()

private fun UiMessage.withoutToolCallsForStandaloneContent(): UiMessage =
    copy(toolCalls = null, approvalRequest = null)

private fun UiMessage.withoutStandaloneContentForToolGroup(): UiMessage =
    copy(content = "")

/**
 * When collapsed, picks the most representative message to show as preview.
 * Skips reasoning bubbles so the user sees tool call output or assistant text
 * rather than hidden chain-of-thought. Falls back to [messages.last] if every
 * message is reasoning or the list only contains one entry.
 */
private fun selectCollapsedPreview(messages: List<UiMessage>): UiMessage {
    // Walk backwards from newest — the first non-reasoning hit is the
    // most relevant preview of what the run actually *did*.
    for (i in messages.lastIndex downTo 0) {
        if (!messages[i].isReasoning) return messages[i]
    }
    return messages.last()
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
private fun RunMessageStepRow(
    message: UiMessage,
    position: GroupPosition,
    runIdentityColor: androidx.compose.ui.graphics.Color,
    drawLineAbove: Boolean,
    drawLineBelow: Boolean,
    renderRow: @Composable (
        message: UiMessage,
        position: GroupPosition,
        rowModifier: Modifier,
    ) -> Unit,
) {
    val dotColor = message.runStepDotColor()
    val icon = remember(message.id, message.role, message.isReasoning, message.toolCalls, message.approvalRequest) {
        message.runStepDotIcon()
    }
    RunStepRow(
        dotColor = dotColor,
        stepDotCenterY = message.runStepDotCenterY(),
        runIdentityColor = runIdentityColor,
        drawLineAbove = drawLineAbove,
        drawLineBelow = drawLineBelow,
    ) { rowModifier ->
        // Touch the icon classification value so the IDE/compiler sees the
        // dependency chain (and so a future visual upgrade can swap the dot
        // for an actual icon trivially).
        @Suppress("UNUSED_EXPRESSION") icon
        renderRow(message, position, rowModifier)
    }
}

@Composable
private fun RunToolCallGroupStepRow(
    step: RunTimelineStep.ToolCallGroup,
    runIdentityColor: androidx.compose.ui.graphics.Color,
    drawLineAbove: Boolean,
    drawLineBelow: Boolean,
    animateRows: Boolean,
    activeApprovalRequestId: String?,
    onApprovalDecision: ((String, List<String>, Boolean, String?) -> Unit)?,
) {
    val dotColor = if (step.pendingApprovalToolCallIds.isNotEmpty()) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary
    }
    RunStepRow(
        dotColor = dotColor,
        stepDotCenterY = CompactToolCallGroupStepDotCenterY,
        runIdentityColor = runIdentityColor,
        drawLineAbove = drawLineAbove,
        drawLineBelow = drawLineBelow,
    ) { rowModifier ->
        CompactToolCallGroupCard(
            toolCalls = step.toolCalls,
            pendingApprovalToolCallIds = step.pendingApprovalToolCallIds,
            modifier = rowModifier,
            approvalRequests = step.approvalRequests,
            activeApprovalRequestId = activeApprovalRequestId,
            onApprovalDecision = onApprovalDecision,
            animateRows = animateRows,
            rowAnimationKeyPrefix = "run|${step.key}",
        )
    }
}

@Composable
private fun RunStepRow(
    dotColor: androidx.compose.ui.graphics.Color,
    stepDotCenterY: androidx.compose.ui.unit.Dp,
    runIdentityColor: androidx.compose.ui.graphics.Color,
    drawLineAbove: Boolean,
    drawLineBelow: Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    val stepDotTopPadding = stepDotCenterY - (StepDotSize / 2f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()

                val cx = RunGutterWidth.toPx() / 2f
                val dotCenterY = stepDotCenterY.toPx()
                val stroke = RunIdentityLineWidth.toPx()
                val dotPattern = PathEffect.dashPathEffect(
                    floatArrayOf(RunIdentityDotLength.toPx(), RunIdentityDotGap.toPx()),
                )
                if (drawLineAbove) {
                    drawLine(
                        color = runIdentityColor,
                        start = Offset(cx, 0f),
                        end = Offset(cx, dotCenterY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                        pathEffect = dotPattern,
                    )
                }
                if (drawLineBelow) {
                    drawLine(
                        color = runIdentityColor,
                        start = Offset(cx, dotCenterY),
                        end = Offset(cx, size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                        pathEffect = dotPattern,
                    )
                }
            },
        verticalAlignment = Alignment.Top,
    ) {
        // Gutter column: a fixed-width box that draws the connector lines
        // and the dot. We keep this layout-stable so successive rows align
        // pixel-perfect (letta-mobile-m772.9: dots centred on the gutter axis,
        // lines on the same axis so there's no horizontal jitter).
        Box(
            modifier = Modifier
                .width(RunGutterWidth),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(stepDotTopPadding))
                Box(
                    modifier = Modifier
                        .size(StepDotSize)
                        .background(dotColor, CircleShape),
                )
            }
        }

        // Right-hand bubble. Caller decides padding inside the row; we just
        // hand them a Modifier that fills the remaining width.
        content(Modifier.fillMaxWidth())
    }
}

private fun UiMessage.runStepDotCenterY() = when {
    !toolCalls.isNullOrEmpty() -> ToolCallStepDotCenterY
    else -> DefaultStepDotCenterY
}
