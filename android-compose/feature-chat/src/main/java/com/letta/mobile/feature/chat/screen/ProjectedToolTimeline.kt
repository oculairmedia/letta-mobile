package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ToolTimelineCall
import com.letta.mobile.data.chat.projection.ToolTimelineGroup
import com.letta.mobile.data.chat.projection.ToolTimelineProjector
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.feature.chat.render.LocalToolCardBodyParentVisible
import com.letta.mobile.feature.chat.render.ToolOutputRenderer
import com.letta.mobile.ui.components.CollapsibleStatusRow
import com.letta.mobile.ui.components.LiveStatusText
import com.letta.mobile.ui.components.StatusTimeline
import com.letta.mobile.ui.components.StatusTimelineItem
import com.letta.mobile.ui.components.TimelineNode
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.customColors

/**
 * Step row adapter that projects tool lifecycle timeline into designsystem timeline primitives.
 * Uses a single component family ([ProjectedToolTimelineGroupCard]) for both 1-call and many-call cases.
 */
@Composable
internal fun ProjectedToolTimelineGroupStepRow(
    step: RunTimelineStep.ToolCallGroup,
    runIdentityColor: Color,
    drawLineAbove: Boolean,
    drawLineBelow: Boolean,
    animateRows: Boolean,
    activeApprovalRequestId: String?,
    onApprovalDecision: ((String, List<String>, Boolean, String?) -> Unit)?,
    modifier: Modifier = Modifier,
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)? = null,
) {
    // Project step.messages using ToolTimelineProjector to get stable, referentially-cached ToolTimelineGroup(s)
    val projector = remember(step.key) { ToolTimelineProjector() }
    val groups = remember(step.messages) {
        projector.project(step.messages)
    }

    // Determine dot color for outer run gutter from overall aggregated group state
    val overallState = groups.firstOrNull()?.state ?: ToolTimelineState.Running
    val dotColor = when (overallState) {
        ToolTimelineState.AwaitingApproval -> MaterialTheme.colorScheme.secondary
        ToolTimelineState.Failed, ToolTimelineState.Rejected -> MaterialTheme.colorScheme.error
        ToolTimelineState.Warning -> MaterialTheme.customColors.warningTextColor
        else -> MaterialTheme.colorScheme.primary
    }

    RunStepRow(
        stepKey = step.key,
        dotColor = dotColor,
        stepDotCenterY = CompactToolCallGroupStepDotCenterY,
        runIdentityColor = runIdentityColor,
        drawLineAbove = drawLineAbove,
        drawLineBelow = drawLineBelow,
    ) { rowModifier ->
        ProjectedToolTimelineGroupCard(
            groups = groups,
            approvalRequests = step.approvalRequests,
            activeApprovalRequestId = activeApprovalRequestId,
            onApprovalDecision = onApprovalDecision,
            modifier = rowModifier.then(modifier).padding(start = 6.dp),
            animateRows = animateRows,
            onAttachmentImageTap = onAttachmentImageTap,
        )
    }
}

/**
 * Group card displaying projected tool calls in a unified designsystem [StatusTimeline].
 * Uses ONE component family regardless of call count (1 call or N calls).
 */
@Composable
internal fun ProjectedToolTimelineGroupCard(
    groups: List<ToolTimelineGroup>,
    approvalRequests: List<UiApprovalRequest> = emptyList(),
    activeApprovalRequestId: String? = null,
    onApprovalDecision: ((String, List<String>, Boolean, String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
    animateRows: Boolean = false,
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.82f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Render all tool groups through a single StatusTimeline component family
            groups.forEach { group ->
                key(group.key) {
                    StatusTimeline(
                        items = group.calls,
                        modifier = Modifier.fillMaxWidth(),
                        key = { call -> call.key },
                    ) { call, isFirst, isLast ->
                        ProjectedToolTimelineCallRow(
                            call = call,
                            isFirst = isFirst,
                            isLast = isLast,
                            onAttachmentImageTap = onAttachmentImageTap,
                        )
                    }
                }
            }

            // Approvals survive hydration; render ApprovalRequestControls if approval requests exist
            approvalRequests.forEach { approval ->
                ApprovalRequestControls(
                    approval = approval,
                    isSubmitting = activeApprovalRequestId == approval.requestId,
                    onDecision = onApprovalDecision,
                )
            }
        }
    }
}

/**
 * Renders a single [ToolTimelineCall] inside a [StatusTimelineItem].
 *
 * Keys come strictly from [ToolTimelineCall.key] (the projector's stable key).
 * Special cards (image attachments and subagent dispatch / notification) deliberately
 * fall back to dedicated rich card composables.
 */
@Composable
private fun ProjectedToolTimelineCallRow(
    call: ToolTimelineCall,
    isFirst: Boolean,
    isLast: Boolean,
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)?,
) {
    // Check for special card fallbacks: image generation and subagent dispatch / notification
    val specialSubagentNotification = remember(call.result) {
        call.result?.let(::parseTaskNotificationForToolCard)
    }
    val isSpecialImageCard = call.generatedImageAttachments.isNotEmpty() || call.name == "generate_image"
    val isSpecialSubagentDispatchCard = call.subagentDispatch != null
    val isSpecialSubagentNotificationCard = specialSubagentNotification != null
    val isSpecialCard = isSpecialImageCard || isSpecialSubagentDispatchCard || isSpecialSubagentNotificationCard

    val (nodeContainerColor, nodeContentColor, nodeIcon) = when (call.state) {
        ToolTimelineState.AwaitingApproval -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            LettaIcons.Help,
        )
        ToolTimelineState.Running -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            LettaIcons.Refresh,
        )
        ToolTimelineState.Succeeded -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            LettaIcons.Check,
        )
        ToolTimelineState.Warning -> Triple(
            MaterialTheme.customColors.warningContainerColor,
            MaterialTheme.customColors.warningTextColor,
            LettaIcons.Warning,
        )
        ToolTimelineState.Failed -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            LettaIcons.Error,
        )
        ToolTimelineState.Rejected -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            LettaIcons.Close,
        )
    }

    StatusTimelineItem(
        node = {
            TimelineNode(
                containerColor = nodeContainerColor,
                contentColor = nodeContentColor,
                icon = nodeIcon,
            )
        },
        showTopConnector = !isFirst,
        showBottomConnector = !isLast,
    ) {
        if (isSpecialCard) {
            // Special cards (image generation and subagent dispatches/notifications) fall back deliberately
            // to their dedicated rich card rendering rather than generic timeline rows.
            if (isSpecialImageCard) {
                GeneratedImageToolCard(
                    toolCall = UiToolCall(
                        name = call.name,
                        arguments = call.arguments,
                        result = call.result,
                        toolCallId = call.toolCallId,
                        generatedImageAttachments = call.generatedImageAttachments,
                        executionTimeMs = call.executionTimeMs,
                        approvalDecision = call.approvalDecision,
                    ),
                    onAttachmentImageTap = onAttachmentImageTap,
                )
            } else if (isSpecialSubagentDispatchCard) {
                SubagentDispatchCard(
                    dispatch = call.subagentDispatch!!,
                    status = call.state.name.lowercase(),
                    executionTimeMs = call.executionTimeMs,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (isSpecialSubagentNotificationCard) {
                SubagentNotificationCard(
                    notification = specialSubagentNotification!!,
                    toolCallId = call.toolCallId,
                    fallbackDescription = call.name,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            // Standard tool call row using CollapsibleStatusRow primitive
            var expanded by remember(call.key) { mutableStateOf(false) }

            // Truncation / full-result fetch on expansion
            val uiToolCall = remember(call) {
                UiToolCall(
                    name = call.name,
                    arguments = call.arguments,
                    result = call.result,
                    toolCallId = call.toolCallId,
                    executionTimeMs = call.executionTimeMs,
                    approvalDecision = call.approvalDecision,
                    resultTruncation = call.resultTruncation,
                )
            }
            RequestFullToolResultOnExpand(toolCall = uiToolCall, expanded = expanded)

            // Heavy-output deferral
            val parentVisible = LocalToolCardBodyParentVisible.current
            val canRenderFullOutput = expanded && parentVisible
            val deferHeavyOutput = call.result != null && !canRenderFullOutput
            val displayResult = remember(call.result, deferHeavyOutput) {
                if (deferHeavyOutput) call.result?.deferredToolResultPreview() else call.result?.displayToolResult()
            }

            val statusLabel = when (call.state) {
                ToolTimelineState.AwaitingApproval -> "Awaiting approval"
                ToolTimelineState.Succeeded -> call.executionTimeMs?.let(::formatToolExecutionTime) ?: "Done"
                ToolTimelineState.Failed -> "Failed"
                ToolTimelineState.Rejected -> "Rejected"
                ToolTimelineState.Warning -> "Warning"
                ToolTimelineState.Running -> null
            }

            val statusColor = when (call.state) {
                ToolTimelineState.Failed, ToolTimelineState.Rejected -> MaterialTheme.colorScheme.error
                ToolTimelineState.Warning -> MaterialTheme.customColors.warningTextColor
                ToolTimelineState.AwaitingApproval -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            CollapsibleStatusRow(
                title = call.summary,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                statusLabel = statusLabel,
                statusColor = statusColor,
                badge = if (call.approvalDecision != null || call.state == ToolTimelineState.AwaitingApproval) {
                    {
                        val chipState = when {
                            call.state == ToolTimelineState.AwaitingApproval -> ToolApprovalState.RequestingInput
                            call.approvalDecision == UiToolApprovalDecision.Approved -> ToolApprovalState.Approved
                            call.approvalDecision == UiToolApprovalDecision.Rejected -> ToolApprovalState.Rejected
                            else -> null
                        }
                        AnimatedToolApprovalChip(state = chipState)
                    }
                } else null,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (call.state == ToolTimelineState.Running) {
                        LiveStatusText(
                            text = "Executing ${call.name}...",
                            active = true,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    val argumentSummary = remember(call.arguments) { summarizeToolArguments(call.arguments) }
                    if (argumentSummary != null) {
                        ToolSummaryLine(
                            label = argumentSummary.label,
                            value = argumentSummary.value,
                            fontScale = LocalChatFontScale.current,
                            isError = call.state == ToolTimelineState.Failed,
                            isWarning = call.state == ToolTimelineState.Warning,
                            maxLines = 4,
                        )
                    }

                    if (displayResult != null) {
                        ToolOutputRenderer(
                            raw = displayResult,
                            expanded = expanded,
                            isError = call.state == ToolTimelineState.Failed || call.state == ToolTimelineState.Rejected,
                        )
                    }
                }
            }
        }
    }
}
