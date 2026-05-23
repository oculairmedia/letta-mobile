package com.letta.mobile.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.feature.chat.R
import com.letta.mobile.data.model.ToolReturnStatus
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.tooloutput.ToolOutputParser
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatBubbleSender
import com.letta.mobile.ui.theme.chatTypography
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.scaledBy
import com.letta.mobile.ui.theme.sectionTitle
import java.util.LinkedHashMap

private const val TOOL_CALL_ENTRANCE_ANIMATION_HISTORY_SIZE = 512

private val toolCallEntranceAnimationHistory =
    RecentStringSet(TOOL_CALL_ENTRANCE_ANIMATION_HISTORY_SIZE)

@Composable
internal fun MessageToolCalls(
    toolCalls: kotlinx.collections.immutable.ImmutableList<UiToolCall>,
    modifier: Modifier = Modifier,
    messageId: String? = null,
    animateEntrance: Boolean = false,
    approvalRequest: UiApprovalRequest? = null,
) {
    val pendingApprovalToolCallIds = remember(approvalRequest) {
        approvalRequest?.toolCalls
            ?.mapTo(mutableSetOf()) { it.toolCallId }
            .orEmpty()
    }
    if (shouldUseCompactToolCallGroup(toolCalls)) {
        val entranceKey = remember(messageId, toolCalls.firstOrNull()?.toolCallMotionKey()) {
            "tool-group|${messageId.orEmpty()}|${toolCalls.firstOrNull()?.toolCallMotionKey().orEmpty()}"
        }
        val shouldAnimateEntrance = remember(animateEntrance, entranceKey) {
            shouldRunToolCallEntranceAnimation(animateEntrance, entranceKey)
        }
        if (shouldAnimateEntrance) {
            ToolCallEntrance {
                CompactToolCallGroupCard(
                    toolCalls = toolCalls,
                    pendingApprovalToolCallIds = pendingApprovalToolCallIds,
                    modifier = modifier,
                    animateRows = animateEntrance,
                    rowAnimationKeyPrefix = "message|${messageId.orEmpty()}",
                )
            }
        } else {
            CompactToolCallGroupCard(
                toolCalls = toolCalls,
                pendingApprovalToolCallIds = pendingApprovalToolCallIds,
                modifier = modifier,
                animateRows = animateEntrance,
                rowAnimationKeyPrefix = "message|${messageId.orEmpty()}",
            )
        }
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        toolCalls.forEachIndexed { index, toolCall ->
            val motionKey = toolCall.toolCallMotionKey()
            key(index, motionKey) {
                val entranceKey = remember(messageId, motionKey) {
                    "tool|${messageId.orEmpty()}|$motionKey"
                }
                val shouldAnimateEntrance = remember(animateEntrance, entranceKey) {
                    shouldRunToolCallEntranceAnimation(animateEntrance, entranceKey)
                }
                val approvalState = toolCall.approvalState(pendingApprovalToolCallIds)
                if (shouldAnimateEntrance) {
                    ToolCallEntrance {
                        ToolCallCard(
                            toolCall = toolCall,
                            approvalStateOverride = approvalState,
                        )
                    }
                } else {
                    ToolCallCard(
                        toolCall = toolCall,
                        approvalStateOverride = approvalState,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ToolCallCard(
    toolCall: UiToolCall,
    approvalStateOverride: ToolApprovalState? = null,
    keepExpanded: Boolean = false,
) {
    val fontScale = LocalChatFontScale.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    var expanded by remember { mutableStateOf(false) }
    val display = remember(toolCall.name, toolCall.arguments) {
        ToolDisplayRegistry.resolve(toolCall.name, toolCall.arguments)
    }
    val argumentSummary = remember(toolCall.arguments) { summarizeToolArguments(toolCall.arguments) }
    val executionTimeText = remember(toolCall.executionTimeMs) { toolCall.executionTimeMs?.let(::formatToolExecutionTime) }
    val displayResult = remember(toolCall.result) { toolCall.result?.displayToolResult() }
    val resultPreview = remember(displayResult) { displayResult?.takeIf { it.isNotBlank() } }
    // Explicit-error-whitelist: only paint the Error icon / red color when
    // the server actually said "error". Treating `null`, "completed", or any
    // unrecognized value as error caused the long-running mis-labeling bug
    // tracked in `letta-mobile-o9ce`. See ToolReturnStatus for the full
    // empirical justification.
    val isError = ToolReturnStatus.isError(toolCall.status)
    val codeStyle = MaterialTheme.chatTypography.codeBlock
    val showDetails = keepExpanded || expanded
    val approvalState = approvalStateOverride ?: toolCall.approvalDecision?.toToolApprovalState()
    val compactDetail = remember(
        display.label,
        display.detailLine,
        toolCall.name,
        argumentSummary,
        resultPreview,
        displayResult,
        isError,
    ) {
        when {
            resultPreview != null -> "${if (isError) "Error" else "Result"}: $resultPreview"
            argumentSummary != null -> "${argumentSummary.label}: ${argumentSummary.value}"
            toolCall.result == null -> "Running"
            display.label != toolCall.name -> display.label
            else -> display.detailLine
        }
    }
    val compactTitle = remember(toolCall.name, compactDetail) {
        compactDetail?.let { "${toolCall.name} - $it" } ?: toolCall.name
    }

    // letta-mobile-23h5 (polish 2026-04-19): give the tool card a touch
    // more presence — slightly stronger surface tint and a 1.dp outline
    // so it reads as a distinct artifact in a stack of bubbles, without
    // shouting like a colored pill would.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.86f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            // Single-line header — tap to expand/collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !keepExpanded) {
                        HapticEffects.segmentTick(haptic, view)
                        expanded = !expanded
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(display.emoji, style = codeStyle)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = compactTitle,
                    style = MaterialTheme.typography.chatBubbleSender.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // letta-mobile-23h5: folded-in approval decision. Rendered as
                // a compact chip so the user can see "approved" / "rejected"
                // without the old stack of redundant standalone pill bubbles.
                // Pending approval requests use the same slot so the tool card
                // animates from "requesting input" to "approved".
                if (approvalState != null) {
                    AnimatedToolApprovalChip(state = approvalState)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                executionTimeText?.let { time ->
                    ToolMetaChip(text = time)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (isError) {
                    Icon(
                        imageVector = LettaIcons.Error,
                        contentDescription = "Error",
                        modifier = Modifier.size(LettaIconSizing.Inline),
                        tint = MaterialTheme.colorScheme.error,
                    )
                } else if (toolCall.result != null) {
                    Icon(
                        imageVector = LettaIcons.CheckCircle,
                        contentDescription = "Success",
                        modifier = Modifier.size(LettaIconSizing.Inline),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // letta-mobile-2o63: animate the expand/collapse with the same
            // ChatMotion ramp + SizeTransform(clip = true) used by
            // ToolOutputRenderer and RunBlock. The previous bare
            // `if (showDetails) { ... }` cut produced a hard pop. The
            // SizeTransform clip prevents the un-collapsing content from
            // overshooting its bounds during the transition, which keeps the
            // LazyColumn's scroll anchor stable (the same trade-off
            // ToolOutputRenderer relies on; see letta-mobile-3wjn). Pinch
            // gestures skip the wrapper so multi-touch height interpolations
            // don't cascade across bubbles.
            val isPinching = LocalChatIsPinching.current
            if (isPinching) {
                ToolCallExpandedBodyContent(
                    visible = showDetails,
                    toolCall = toolCall,
                    argumentSummary = argumentSummary,
                    resultPreview = resultPreview,
                    isError = isError,
                    fontScale = fontScale,
                    codeStyle = codeStyle,
                    display = display,
                    executionTimeText = executionTimeText,
                    displayResult = displayResult,
                )
            } else {
                AnimatedContent(
                    targetState = showDetails,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = {
                        (ChatMotion.expandEnter() togetherWith ChatMotion.expandExit())
                            .using(SizeTransform(clip = true) { _, _ -> ChatMotion.contentSizeSpec })
                    },
                    contentAlignment = Alignment.TopStart,
                    label = "ToolCallCardExpanded",
                ) { expandedNow ->
                    ToolCallExpandedBodyContent(
                        visible = expandedNow,
                        toolCall = toolCall,
                        argumentSummary = argumentSummary,
                        resultPreview = resultPreview,
                        isError = isError,
                        fontScale = fontScale,
                        codeStyle = codeStyle,
                        display = display,
                        executionTimeText = executionTimeText,
                        displayResult = displayResult,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallExpandedBodyContent(
    visible: Boolean,
    toolCall: UiToolCall,
    argumentSummary: ToolArgumentSummary?,
    resultPreview: String?,
    isError: Boolean,
    fontScale: Float,
    codeStyle: androidx.compose.ui.text.TextStyle,
    display: ToolDisplayInfo,
    executionTimeText: String?,
    displayResult: String?,
) {
    if (!visible) return
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    Column {
        ToolCallExpandedSummary(
            toolCall = toolCall,
            argumentSummary = argumentSummary,
            resultPreview = resultPreview,
            isError = isError,
            fontScale = fontScale,
        )
        Column(modifier = Modifier.padding(top = 4.dp)) {
            // Tool name and timing
            Text(
                text = "Tool: ${toolCall.name}",
                style = MaterialTheme.typography.chatBubbleSender.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
            )
            executionTimeText?.let { time ->
                Text(
                    text = "Execution time: $time",
                    style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
                )
            }
            // Detail line (extracted from arguments)
            display.detailLine?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Arguments
            if (toolCall.arguments.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Arguments",
                    style = MaterialTheme.typography.sectionTitle.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
                Text(
                    text = toolCall.arguments,
                    style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Result — inner collapsible (letta-mobile-mge5.19).
            // Default collapsed: show the result-label row with a
            // chevron + first-line preview. Tap expands to full.
            displayResult?.takeIf { it.isNotBlank() }?.let { result ->
                var resultExpanded by remember(toolCall.result) { mutableStateOf(false) }
                val resultChevronRotation by animateFloatAsState(
                    targetValue = if (resultExpanded) 180f else 0f,
                    animationSpec = ChatMotion.chipCrossfadeSpec,
                    label = "ToolOutputChevronRotation",
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            HapticEffects.segmentTick(haptic, view)
                            resultExpanded = !resultExpanded
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isError) "Error" else "Output",
                        style = MaterialTheme.typography.sectionTitle.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                        color = if (isError) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    val lineCount = result.count { it == '\n' } + 1
                    if (lineCount > 1 || result.length > 80) {
                        Text(
                            text = if (resultExpanded) "collapse" else "${lineCount} line${if (lineCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall.scaledBy(fontScale),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Icon(
                        imageVector = LettaIcons.ExpandMore,
                        contentDescription = if (resultExpanded) "Collapse output" else "Expand output",
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(resultChevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
                ToolOutputRenderer(
                    raw = result,
                    expanded = resultExpanded,
                    isError = isError,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun ToolCallExpandedBody(
    toolCall: UiToolCall,
    display: ToolDisplayInfo,
    executionTimeText: String?,
    displayResult: String?,
    isError: Boolean,
    fontScale: Float,
) {
    val codeStyle = MaterialTheme.chatTypography.codeBlock
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Text(
            text = "Tool: ${toolCall.name}",
            style = MaterialTheme.typography.chatBubbleSender.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
        )
        executionTimeText?.let { time ->
            Text(
                text = "Execution time: $time",
                style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
            )
        }
        display.detailLine?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (toolCall.arguments.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Arguments",
                style = MaterialTheme.typography.sectionTitle.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
            Text(
                text = toolCall.arguments,
                style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        displayResult?.takeIf { it.isNotBlank() }?.let { result ->
            var resultExpanded by remember(toolCall.result) { mutableStateOf(false) }
            val resultChevronRotation by animateFloatAsState(
                targetValue = if (resultExpanded) 180f else 0f,
                animationSpec = ChatMotion.chipCrossfadeSpec,
                label = "ToolOutputChevronRotation",
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { }
                    .clickable {
                        HapticEffects.segmentTick(haptic, view)
                        resultExpanded = !resultExpanded
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isError) "Error" else "Output",
                    style = MaterialTheme.typography.sectionTitle.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                    color = if (isError) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    },
                    modifier = Modifier.weight(1f),
                )
                val lineCount = result.count { it == '\n' } + 1
                if (lineCount > 1 || result.length > 80) {
                    Text(
                        text = if (resultExpanded) "collapse" else "${lineCount} line${if (lineCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall.scaledBy(fontScale),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Icon(
                    imageVector = LettaIcons.ExpandMore,
                    contentDescription = if (resultExpanded) "Collapse output" else "Expand output",
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(resultChevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
            ToolOutputRenderer(
                raw = result,
                expanded = resultExpanded,
                isError = isError,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun ToolCallExpandedSummary(
    toolCall: UiToolCall,
    argumentSummary: ToolArgumentSummary?,
    resultPreview: String?,
    isError: Boolean,
    fontScale: Float,
) {
    argumentSummary?.let { summary ->
        Spacer(modifier = Modifier.height(6.dp))
        ToolSummaryLine(
            label = summary.label,
            value = summary.value,
            fontScale = fontScale,
            maxLines = 2,
        )
    }
    if (resultPreview != null) {
        Spacer(modifier = Modifier.height(4.dp))
        ToolSummaryLine(
            label = if (isError) "Error" else "Result",
            value = resultPreview,
            fontScale = fontScale,
            isError = isError,
            maxLines = 2,
        )
    } else if (toolCall.result == null) {
        Spacer(modifier = Modifier.height(4.dp))
        ToolSummaryLine(
            label = "Status",
            value = "Running",
            fontScale = fontScale,
            maxLines = 1,
        )
    }
}

@Composable
internal fun CompactToolCallGroupCard(
    toolCalls: List<UiToolCall>,
    pendingApprovalToolCallIds: Set<String>,
    modifier: Modifier = Modifier,
    approvalRequests: List<UiApprovalRequest> = emptyList(),
    activeApprovalRequestId: String? = null,
    onApprovalDecision: ((String, List<String>, Boolean, String?) -> Unit)? = null,
    animateRows: Boolean = false,
    rowAnimationKeyPrefix: String = "",
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${toolCalls.size} tool calls",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                val completedCount = toolCalls.count { it.result != null }
                if (completedCount > 0) {
                    ToolMetaChip(text = "$completedCount/${toolCalls.size} done")
                }
            }
            toolCalls.forEachIndexed { index, toolCall ->
                val motionKey = toolCall.toolCallMotionKey()
                key(index, motionKey) {
                    val entranceKey = remember(rowAnimationKeyPrefix, motionKey) {
                        "compact-tool-row|$rowAnimationKeyPrefix|$motionKey"
                    }
                    val shouldAnimateEntrance = remember(animateRows, entranceKey) {
                        shouldRunToolCallEntranceAnimation(animateRows, entranceKey)
                    }
                    if (shouldAnimateEntrance) {
                        ToolCallEntrance {
                            CompactToolCallRow(
                                toolCall = toolCall,
                                approvalState = toolCall.approvalState(pendingApprovalToolCallIds),
                            )
                        }
                    } else {
                        CompactToolCallRow(
                            toolCall = toolCall,
                            approvalState = toolCall.approvalState(pendingApprovalToolCallIds),
                        )
                    }
                }
            }
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

@Composable
internal fun CompactToolCallRow(
    toolCall: UiToolCall,
    approvalState: ToolApprovalState?,
) {
    val fontScale = LocalChatFontScale.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    var expanded by remember(toolCall.toolCallMotionKey()) { mutableStateOf(false) }
    val display = remember(toolCall.name, toolCall.arguments) {
        ToolDisplayRegistry.resolve(toolCall.name, toolCall.arguments)
    }
    val argumentSummary = remember(toolCall.arguments) { summarizeToolArguments(toolCall.arguments) }
    val executionTimeText = remember(toolCall.executionTimeMs) { toolCall.executionTimeMs?.let(::formatToolExecutionTime) }
    val displayResult = remember(toolCall.result) { toolCall.result?.displayToolResult() }
    val resultPreview = remember(displayResult) { displayResult?.takeIf { it.isNotBlank() } }
    val summary = remember(toolCall.name, toolCall.arguments, displayResult, toolCall.status, display.detailLine) {
        compactToolCallSummary(toolCall, display.detailLine, displayResult)
    }
    val compactTitle = remember(toolCall.name, summary) {
        "${toolCall.name} - $summary"
    }
    val isError = ToolReturnStatus.isError(toolCall.status)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = ChatMotion.chipCrossfadeSpec,
        label = "CompactToolCallChevronRotation",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { }
                .clickable(
                    onClickLabel = if (expanded) "Collapse tool details" else "Expand tool details",
                ) {
                    HapticEffects.segmentTick(haptic, view)
                    expanded = !expanded
                }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(display.emoji, style = MaterialTheme.chatTypography.codeBlock)
            Text(
                text = compactTitle,
                style = MaterialTheme.typography.chatBubbleSender
                    .copy(fontFamily = MaterialTheme.chatTypography.codeBlock.fontFamily)
                    .scaledBy(fontScale),
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // letta-mobile-gbsq: suppress the "Approved" chip once the tool has
            // produced a result — the success checkmark conveys the same
            // information and dropping the chip reclaims width for the result
            // preview. RequestingInput and Rejected still render (they carry
            // information the checkmark can't).
            if (shouldShowCompactApprovalChip(approvalState, hasResult = toolCall.result != null)) {
                AnimatedToolApprovalChip(state = approvalState)
            }
            executionTimeText?.let { time ->
                ToolMetaChip(text = time)
            }
            when {
                isError -> Icon(
                    imageVector = LettaIcons.Error,
                    contentDescription = "Error",
                    modifier = Modifier.size(LettaIconSizing.Inline),
                    tint = MaterialTheme.colorScheme.error,
                )
                toolCall.result != null -> Icon(
                    imageVector = LettaIcons.CheckCircle,
                    // Preserve the approval semantic for screen readers even
                    // when the visual chip is suppressed for a completed
                    // approved row.
                    contentDescription = if (approvalState == ToolApprovalState.Approved) {
                        "Approved, success"
                    } else {
                        "Success"
                    },
                    modifier = Modifier.size(LettaIconSizing.Inline),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = LettaIcons.ExpandMore,
                contentDescription = if (expanded) "Collapse tool details" else "Expand tool details",
                modifier = Modifier
                    .size(14.dp)
                    .rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
            ) {
                ToolCallExpandedSummary(
                    toolCall = toolCall,
                    argumentSummary = argumentSummary,
                    resultPreview = resultPreview,
                    isError = isError,
                    fontScale = fontScale,
                )
                ToolCallExpandedBody(
                    toolCall = toolCall,
                    display = display,
                    executionTimeText = executionTimeText,
                    displayResult = displayResult,
                    isError = isError,
                    fontScale = fontScale,
                )
            }
        }
    }
}

internal fun compactToolCallSummary(
    toolCall: UiToolCall,
    displayDetailLine: String?,
    displayResult: String? = toolCall.result?.displayToolResult(),
): String {
    val result = displayResult?.takeIf { it.isNotBlank() }
    if (result != null) return "${if (ToolReturnStatus.isError(toolCall.status)) "Error" else "Result"}: $result"
    val argumentSummary = summarizeToolArguments(toolCall.arguments)
    if (argumentSummary != null) return "${argumentSummary.label}: ${argumentSummary.value}"
    return displayDetailLine ?: if (toolCall.result == null) "Running" else "Completed"
}

internal fun String.displayToolResult(): String = ToolOutputParser.sanitizeResultFieldText(this)

internal fun shouldUseCompactToolCallGroup(toolCalls: List<UiToolCall>): Boolean =
    toolCalls.size > 1

internal fun shouldRunToolCallEntranceAnimation(
    animateEntrance: Boolean,
    key: String,
): Boolean =
    animateEntrance && toolCallEntranceAnimationHistory.addIfAbsent(key)

internal fun recordToolCallEntranceAnimationRun(key: String) {
    toolCallEntranceAnimationHistory.addIfAbsent(key)
}

internal fun clearToolCallEntranceAnimationHistoryForTest() {
    toolCallEntranceAnimationHistory.clear()
}

internal fun UiToolCall.toolCallMotionKey(): String = buildString {
    append(toolCallId ?: name)
    append('|')
    append(arguments.hashCode())
}

@Composable
internal fun ToolCallEntrance(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = ChatMotion.verticalEnter(slideDivisor = 10),
        exit = ChatMotion.expandExit(),
    ) {
        content()
    }
}

@Composable
internal fun ToolApprovalChip(state: ToolApprovalState) {
    val container = when (state) {
        ToolApprovalState.RequestingInput -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        ToolApprovalState.Approved -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        ToolApprovalState.Rejected -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
    }
    val text = when (state) {
        ToolApprovalState.RequestingInput -> stringResource(R.string.screen_chat_tool_approval_chip_requesting_input)
        ToolApprovalState.Approved -> stringResource(R.string.screen_chat_tool_approval_chip_approved)
        ToolApprovalState.Rejected -> stringResource(R.string.screen_chat_tool_approval_chip_rejected)
    }
    val tint = when (state) {
        ToolApprovalState.RequestingInput -> MaterialTheme.colorScheme.onSecondaryContainer
        ToolApprovalState.Approved -> MaterialTheme.colorScheme.primary
        ToolApprovalState.Rejected -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun ToolMetaChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ToolSummaryLine(
    label: String,
    value: String,
    fontScale: Float,
    isError: Boolean = false,
    maxLines: Int = 1,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.sectionTitle.scaledBy(fontScale),
            color = if (isError) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
            },
        )
        Text(
            text = value,
            style = MaterialTheme.typography.listItemSupporting.scaledBy(fontScale),
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

internal data class ToolArgumentSummary(val label: String, val value: String)

internal fun summarizeToolArguments(arguments: String): ToolArgumentSummary? {
    if (arguments.isBlank()) return null
    val fields = listOf(
        "query" to "Query",
        "search" to "Query",
        "command" to "Args",
        "file_path" to "Args",
        "pattern" to "Args",
        "content" to "Args",
        "value" to "Args",
    )
    fields.forEach { (field, label) ->
        extractJsonStringField(arguments, field)?.let { value ->
            return ToolArgumentSummary(label = label, value = value)
        }
    }
    return ToolArgumentSummary(label = "Args", value = arguments.trim())
}

internal fun extractJsonStringField(json: String, field: String): String? {
    val key = "\"$field\""
    val keyIdx = json.indexOf(key)
    if (keyIdx < 0) return null
    val colonIdx = json.indexOf(':', keyIdx + key.length)
    if (colonIdx < 0) return null
    val quoteStart = json.indexOf('"', colonIdx + 1)
    if (quoteStart < 0) return null
    val sb = StringBuilder()
    var i = quoteStart + 1
    while (i < json.length) {
        val c = json[i]
        if (c == '\\' && i + 1 < json.length) {
            val next = json[i + 1]
            when (next) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                'n' -> sb.append(' ')
                't' -> sb.append(' ')
                else -> {
                    sb.append('\\')
                    sb.append(next)
                }
            }
            i += 2
        } else if (c == '"') {
            break
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString().ifBlank { null }
}

internal fun formatToolExecutionTime(durationMs: Long): String {
    return when {
        durationMs < 1_000L -> "${durationMs}ms"
        durationMs < 60_000L -> {
            val seconds = durationMs / 1_000.0
            "${String.format(java.util.Locale.US, "%.1f", seconds)}s"
        }
        else -> {
            val minutes = durationMs / 60_000L
            val seconds = (durationMs % 60_000L) / 1_000L
            "${minutes}m ${seconds}s"
        }
    }
}

internal fun UiToolApprovalDecision.toToolApprovalState(): ToolApprovalState = when (this) {
    UiToolApprovalDecision.Approved -> ToolApprovalState.Approved
    UiToolApprovalDecision.Rejected -> ToolApprovalState.Rejected
}

internal fun UiToolCall.approvalState(pendingApprovalToolCallIds: Set<String>): ToolApprovalState? {
    val id = toolCallId?.takeIf { it.isNotBlank() }
    if (id != null && id in pendingApprovalToolCallIds) {
        return ToolApprovalState.RequestingInput
    }
    return approvalDecision?.toToolApprovalState()
}

/**
 * Whether to render the inline approval chip in a [CompactToolCallRow].
 *
 * - `RequestingInput` always renders — the user needs to know an approval is
 *   pending.
 * - `Rejected` always renders — the decision is the row's payload.
 * - `Approved` renders only while the tool is still running; once a result is
 *   in hand, the success checkmark on the same row carries the same signal
 *   and dropping the chip reclaims width for the result preview.
 * - `null` (no approval involvement) never shows a chip.
 */
internal fun shouldShowCompactApprovalChip(
    approvalState: ToolApprovalState?,
    hasResult: Boolean,
): Boolean = when (approvalState) {
    null -> false
    ToolApprovalState.RequestingInput -> true
    ToolApprovalState.Rejected -> true
    ToolApprovalState.Approved -> !hasResult
}

/**
 * Compact, inline approval chip shown in the `ToolCallCard` header. Pending
 * requests and folded decisions share the same slot so the chip can crossfade
 * from "requesting input" to "approved" instead of popping in as new chrome.
 */
@Composable
internal fun AnimatedToolApprovalChip(state: ToolApprovalState?) {
    AnimatedVisibility(
        visible = state != null,
        enter = ChatMotion.horizontalEnter(),
        exit = ChatMotion.horizontalExit(),
    ) {
        Crossfade(
            targetState = state,
            animationSpec = ChatMotion.chipCrossfadeSpec,
            label = "Tool approval chip",
        ) { targetState ->
            targetState?.let { ToolApprovalChip(it) }
        }
    }
}

internal enum class ToolApprovalState {
    RequestingInput,
    Approved,
    Rejected,
}

private class RecentStringSet(
    private val maxEntries: Int,
) {
    private val lock = Any()
    private val values = object : LinkedHashMap<String, Unit>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > maxEntries
    }

    fun contains(value: String): Boolean = synchronized(lock) {
        values.containsKey(value)
    }

    fun addIfAbsent(value: String): Boolean = synchronized(lock) {
        if (values.containsKey(value)) {
            false
        } else {
            values[value] = Unit
            true
        }
    }

    fun clear() {
        synchronized(lock) {
            values.clear()
        }
    }
}
