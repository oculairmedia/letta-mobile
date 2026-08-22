package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.preview.LettaPreviewFrame
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.letta.mobile.ui.theme.LettaChatTheme

/**
 * Renders a contiguous run of assistant messages sharing a `runId` as a
 * single grouped block under one run disclosure.
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
 * Measurement strategy: each row owns its gutter dot and line segments using
 * deterministic per-role anchors. The block deliberately avoids an overlay
 * `onGloballyPositioned` map, so streaming appends cannot produce a first-frame
 * y=0 dot or remeasure existing step positions.
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
    chatMode: String = "interactive",
    showCompletedDisclosure: Boolean = true,
    renderRow: @Composable (
        message: UiMessage,
        position: GroupPosition,
        rowModifier: Modifier,
    ) -> Unit,
) {
    if (messages.isEmpty()) return

    // letta-mobile-7kpxn (polish audit): honour the reduced-motion contract on
    // the run expand/collapse the same way the tool-card lifecycle does â€” when
    // the OS animation scale is 0, swap instantly instead of playing the ramp.
    val reducedMotion = rememberReducedMotionEnabled()

    // Stable run keys can also wrap one message. The disclosure still renders
    // for that work, while the body avoids a degenerate one-dot gutter below.
    val activity = remember(messages, isStreaming) {
        projectRunActivity(messages, isStreaming)
    } ?: return
    // Active work is not a complete disclosure body yet. Ignore a stale
    // auto-collapse flag until completion without mutating the caller-owned
    // expansion state.
    val collapsible = messages.size > 1
    // letta-mobile-tz1sp (refined Simple projection): Aether's post-turn shape
    // collapses the disclosure once the run has settled so the assistant prose
    // is the primary surface. We track the user's "I want this expanded"
    // override locally so the Simple auto-fold defaults new runs while still
    // honouring an explicit tap-to-expand. Interactive/Debug keep the prior
    // caller-owned `collapsed` semantics across the same boundary.
    var userExpandedOverride by remember(messages) { mutableStateOf(false) }
    val effectiveCollapsed = when {
        !collapsible -> false
        activity.isActive -> false
        chatMode == "simple" -> !userExpandedOverride
        else -> collapsed
    }
    val toggleCollapsed: () -> Unit = {
        if (chatMode == "simple") {
            userExpandedOverride = !userExpandedOverride
        } else {
            onToggleCollapsed()
        }
    }

    // Keep the run container height static from Compose's perspective. Lazy
    // timeline recycling and manual tool-output expansion must not replay run
    // entrance motion or animate the entire block around the user's scroll
    // position.
    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        if (activity.isActive || showCompletedDisclosure) {
            RunActivityDisclosure(
                activity = activity,
                collapsed = effectiveCollapsed,
                collapsible = collapsible,
                onToggleCollapsed = toggleCollapsed,
                chatMode = chatMode,
            )
        }

        // Keep the historical one-step geometry: the disclosure is additive,
        // but a single message still has no degenerate gutter or connector.
        //
        // Exception (letta-mobile-8kdjm.7): a lone tool-call message must still
        // reach the projected timeline rather than fall out to a plain message
        // row — otherwise a one-tool-call turn shows none of the timeline
        // presentation. Non-tool single messages keep the original short circuit.
        val singleToolCallGoesToTimeline = messages.size == 1 &&
            messages.single().isRunCompactableToolCallMessage()
        if (messages.size == 1 && !singleToolCallGoesToTimeline) {
            renderRow(messages.single(), GroupPosition.None, Modifier.fillMaxWidth())
            return@Column
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            // Timeline gutter â€” drawn behind the rows so the vertical rule
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
                // uniformly removes that swap entirely â€” when collapsed we
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
                    targetState = effectiveCollapsed,
                    transitionSpec = {
                        if (reducedMotion) {
                            (ChatMotion.instantEnter() togetherWith ChatMotion.instantExit())
                                .using(SizeTransform(clip = true) { _, _ -> ChatMotion.instantSizeSpec })
                        } else {
                            (ChatMotion.expandEnter() togetherWith ChatMotion.expandExit())
                                .using(SizeTransform(clip = true) { _, _ -> ChatMotion.contentSizeSpec })
                        }
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
                                when (step) {
                                    is RunTimelineStep.Message -> RunMessageStepRow(
                                        message = step.message,
                                        position = position,
                                        renderRow = renderRow,
                                    )

                                    is RunTimelineStep.ToolCallGroup -> ProjectedToolTimelineGroupStepRow(
                                        step = step,
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

/**
 * Compacts a run's messages into timeline steps, grouping contiguous tool-call
 * messages (including a lone tool call — letta-mobile-8kdjm.7) into a single
 * [RunTimelineStep.ToolCallGroup] so they render through one component family
 * ([ProjectedToolTimelineGroupCard]) regardless of call count.
 */
internal fun compactRunToolCallSteps(
    messages: List<UiMessage>,
): List<RunTimelineStep> {
    if (messages.isEmpty()) return emptyList()
    val steps = ArrayList<RunTimelineStep>(messages.size)
    val toolMessages = messages.mapNotNull { message ->
        when {
            message.isRunCompactableToolCallMessage() -> message
            message.hasStandaloneContentAndToolCalls() -> message.withoutStandaloneContentForToolGroup()
            else -> null
        }
    }
    var emittedToolGroup = false

    messages.forEach { message ->
        when {
            message.isRunCompactableToolCallMessage() -> {
                if (!emittedToolGroup) {
                    steps += toolMessages.toToolCallGroup()
                    emittedToolGroup = true
                }
            }
            message.hasStandaloneContentAndToolCalls() -> {
                if (!emittedToolGroup) {
                    steps += toolMessages.toToolCallGroup()
                    emittedToolGroup = true
                }
                steps.add(RunTimelineStep.Message(message.withoutToolCallsForStandaloneContent()))
            }
            else -> steps.add(RunTimelineStep.Message(message))
        }
    }
    return steps
}

private fun List<UiMessage>.toToolCallGroup() = RunTimelineStep.ToolCallGroup(
    messages = this,
    toolCalls = flatMap { it.toolCalls.orEmpty() },
    pendingApprovalToolCallIds = flatMap { it.approvalRequest?.toolCalls.orEmpty() }
        .mapTo(mutableSetOf()) { it.toolCallId },
    approvalRequests = mapNotNull { it.approvalRequest }.distinctBy { it.requestId },
)

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
    // Walk backwards from newest â€” the first non-reasoning hit is the
    // most relevant preview of what the run actually *did*.
    for (i in messages.lastIndex downTo 0) {
        if (!messages[i].isReasoning) return messages[i]
    }
    return messages.last()
}

/**
 * One step row: gutter on the left (dot + connector segments) and the
 * caller-supplied bubble on the right.
 */
@Composable
private fun RunMessageStepRow(
    message: UiMessage,
    position: GroupPosition,
    renderRow: @Composable (
        message: UiMessage,
        position: GroupPosition,
        rowModifier: Modifier,
    ) -> Unit,
) {
    renderRow(message, position, Modifier.fillMaxWidth())
}

// region Previews

private data class PreviewRunMessageSpec(
    val id: String,
    val content: String,
    val runId: String = "preview-run",
    val stepId: String? = "step-$id",
    val latencyMs: Long? = null,
)

private data class PreviewRunMessages(
    val stepContents: List<String>,
) {
    fun toUiMessages(): List<UiMessage> = stepContents.mapIndexed { index, content ->
        previewRunMessage(PreviewRunMessageSpec(id = "step-${index + 1}", content = content))
    }
}

private fun previewRunMessage(spec: PreviewRunMessageSpec): UiMessage = UiMessage(
    id = spec.id,
    role = "assistant",
    content = spec.content,
    timestamp = "2026-08-08T00:00:00Z",
    runId = spec.runId,
    stepId = spec.stepId,
    latencyMs = spec.latencyMs,
)

@Composable
private fun previewRunBubble(message: UiMessage, position: GroupPosition, rowModifier: Modifier) {
    // Bubble geometry mirrors `MessageBubbleSurface` enough to keep the
    // timeline dot anchored on the first text baseline
    // (DefaultStepDotCenterY = 17.dp). Anything heavier than 7.dp vertical
    // padding pushes the text below the dot.
    androidx.compose.material3.Surface(
        modifier = rowModifier.padding(vertical = 7.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    ) {
        androidx.compose.material3.Text(
            text = message.content,
            modifier = androidx.compose.ui.Modifier.padding(horizontal = 12.dp),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PreviewRunBlock(spec: PreviewRunBlockSpec) {
    LettaPreviewFrame {
        LettaChatTheme {
            // -10.dp pulls the disclosure header's trailing minHeight padding
            // up against the first step's text so the preview is tighter than
            // the runtime layout. The production gap stays untouched.
            RunBlock(
                messages = spec.messages.toUiMessages(),
                collapsed = !spec.expanded,
                onToggleCollapsed = {},
                isStreaming = spec.isStreaming,
                modifier = Modifier.offset(y = (-10).dp),
                renderRow = ::previewRunBubble,
            )
        }
    }
}

private data class PreviewRunBlockSpec(
    val messages: PreviewRunMessages,
    val expanded: Boolean = true,
    val isStreaming: Boolean = false,
)

private val previewRunBlockThreeStepMessages = PreviewRunMessages(
    stepContents = listOf(
        "I will search the codebase for matching patterns.",
        "Found three candidates, let me check each.",
        "All three confirmed, here is the summary.",
    ),
)

@Composable
private fun PreviewRunBlockMultiStep(expanded: Boolean) = PreviewRunBlock(
    PreviewRunBlockSpec(
        messages = previewRunBlockThreeStepMessages,
        expanded = expanded,
    ),
)

@PreviewLightDark
@Composable
private fun RunBlockExpandedPreview() {
    PreviewRunBlockMultiStep(expanded = true)
}

@PreviewLightDark
@Composable
private fun RunBlockCollapsedPreview() {
    PreviewRunBlockMultiStep(expanded = false)
}

@PreviewLightDark
@Composable
private fun RunBlockWorkingPreview() {
    PreviewRunBlock(
        PreviewRunBlockSpec(
            messages = PreviewRunMessages(
                stepContents = listOf("Starting the diagnostic sweep…"),
            ),
            expanded = true,
            isStreaming = true,
        ),
    )
}

// endregion
