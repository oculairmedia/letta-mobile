package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.feature.chat.R
import com.letta.mobile.data.model.ToolReturnStatus
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.tooloutput.ToolOutputParser
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
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
import com.letta.mobile.util.Telemetry
import java.util.LinkedHashMap
import kotlinx.collections.immutable.toImmutableList
import com.letta.mobile.ui.chat.render.ToolDisplayInfo
import com.letta.mobile.ui.chat.render.ToolDisplayRegistry
import com.letta.mobile.feature.chat.render.LocalToolCardBodyParentVisible
import com.letta.mobile.feature.chat.render.LocalToolCardBodyRenderEligibility
import com.letta.mobile.feature.chat.render.ToolOutputRenderer
import com.letta.mobile.feature.chat.render.toolCardBodyRenderEligibility
import com.letta.mobile.feature.chat.subagent.LocalSubagentTodoSheetOpener
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetTarget
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.shimmerColor

private const val TOOL_CALL_ENTRANCE_ANIMATION_HISTORY_SIZE = 512

private val toolCallEntranceAnimationHistory =
    RecentStringSet(TOOL_CALL_ENTRANCE_ANIMATION_HISTORY_SIZE)

internal val LocalChatShouldDeferHeavyToolCards = compositionLocalOf { false }

@Composable
internal fun MessageToolCalls(
    toolCalls: kotlinx.collections.immutable.ImmutableList<UiToolCall>,
    modifier: Modifier = Modifier,
    messageId: String? = null,
    animateEntrance: Boolean = false,
    approvalRequest: UiApprovalRequest? = null,
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)? = null,
) {
    val pendingApprovalToolCallIds = remember(approvalRequest) {
        approvalRequest?.toolCalls
            ?.mapTo(mutableSetOf()) { it.toolCallId }
            .orEmpty()
    }
    val reducedMotion = rememberReducedMotionEnabled()
    // letta-mobile-7kpxn: a streaming message can flip from a single
    // ToolCallCard to the multi-tool CompactToolCallGroupCard as additional
    // tool calls land. Previously this was a bare `if/else` swap that popped
    // the new layout in with no transition. Wrap the single<->group decision
    // in an AnimatedContent keyed *only* on the grouped flag so that:
    //  - flipping single -> group (or back) crossfades + size-morphs through
    //    the canonical ChatMotion content ramp instead of snapping, and
    //  - streaming token / result updates that flow through the *same* branch
    //    do NOT re-trigger the transition (the targetState is unchanged), so
    //    we keep the rmzmo streaming-jank guarantees (cheap, draw-phase, no
    //    per-frame full rebuild).
    // Reduced-motion users get an instant swap (no animation) per the design
    // system's reduced-motion contract.
    val useCompactGroup = shouldUseCompactToolCallGroup(toolCalls)
    AnimatedContent(
        targetState = useCompactGroup,
        modifier = modifier,
        transitionSpec = {
            if (reducedMotion) {
                (ChatMotion.instantEnter() togetherWith ChatMotion.instantExit())
                    .using(SizeTransform(clip = true) { _, _ -> ChatMotion.instantSizeSpec })
            } else {
                // Soft crossfade + height morph so the single card visually
                // "becomes" the grouped card rather than being replaced.
                (ChatMotion.expandEnter() togetherWith ChatMotion.expandExit())
                    .using(SizeTransform(clip = true) { _, _ -> ChatMotion.contentSizeSpec })
            }
        },
        contentAlignment = Alignment.TopStart,
        label = "ToolCallsSingleVsGroup",
    ) { grouped ->
        if (grouped) {
            val entranceKey = remember(messageId, toolCalls.firstOrNull()?.toolCallMotionKey()) {
                "tool-group|${messageId.orEmpty()}|${toolCalls.firstOrNull()?.toolCallMotionKey().orEmpty()}"
            }
            val shouldAnimateEntrance = remember(animateEntrance, entranceKey) {
                shouldRunToolCallEntranceAnimation(animateEntrance, entranceKey)
            }
            ToolCallEntrance(animate = shouldAnimateEntrance && !reducedMotion) {
                CompactToolCallGroupCard(
                    toolCalls = toolCalls,
                    pendingApprovalToolCallIds = pendingApprovalToolCallIds,
                    modifier = Modifier,
                    animateRows = animateEntrance,
                    rowAnimationKeyPrefix = "message|${messageId.orEmpty()}",
                    onAttachmentImageTap = onAttachmentImageTap,
                )
            }
        } else {
            Column(
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
                        ToolCallEntrance(animate = shouldAnimateEntrance && !reducedMotion) {
                            ToolCallCard(
                                toolCall = toolCall,
                                approvalStateOverride = approvalState,
                                onAttachmentImageTap = onAttachmentImageTap,
                            )
                        }
                    }
                }
            }
            }
        }
    }
/**
 * Agent-return card for a completed (or failed) subagent.
 *
 * letta-mobile-rnocg: this is the RETURN half of the subagent dispatch chrome.
 * The server injects the subagent's result as a `<task-notification>` envelope
 * (status / summary / result / usage / transcript / task & agent ids). Rendered
 * as the default inbound message it became a giant green right-aligned USER
 * bubble full of raw XML + the agent's markdown report. Here it renders as a
 * dedicated, recede-by-default agent-return card:
 *  - structured header: status badge, "Subagent completed/failed", usage chips,
 *  - the summary as receding supporting text (always visible),
 *  - the full markdown report rendered as a COLLAPSIBLE section, collapsed by
 *    default — tap "Show full report" to expand.
 *
 * The header row remains tappable to open the dispatch's todo sheet / subagent
 * conversation (correlated by toolCallId/taskId). The report toggle is a
 * separate hit target so expanding the report does not also open the sheet.
 */
@Composable
internal fun SubagentNotificationCard(
    notification: UiSubagentNotification,
    toolCallId: String? = null,
    fallbackDescription: String = "Subagent",
    modifier: Modifier = Modifier,
) {
    val opener = LocalSubagentTodoSheetOpener.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val effectiveToolCallId = toolCallId ?: notification.toolCallId ?: notification.taskId
    val canOpenSubagent = !effectiveToolCallId.isNullOrBlank()
    val openSubagent = {
        val targetId = effectiveToolCallId
        if (!targetId.isNullOrBlank()) {
            HapticEffects.segmentTick(haptic, view)
            opener(
                SubagentTodoSheetTarget(
                    toolCallId = targetId,
                    description = notification.summary ?: fallbackDescription,
                    subagentAgentId = notification.subagentAgentId,
                )
            )
        }
    }
    val headerOpenTodosModifier = if (canOpenSubagent) Modifier.clickable { openSubagent() } else Modifier
    val isFailure = notification.status.equals("failed", ignoreCase = true) ||
        notification.status.equals("error", ignoreCase = true)
    // Collapse the full report by default — the summary already conveys the
    // outcome; the report is opt-in so a long markdown dump never floods the
    // timeline (recede-by-default).
    var reportExpanded by remember(notification.taskId, effectiveToolCallId, notification.result) {
        mutableStateOf(false)
    }
    val report = notification.result?.takeIf { it.isNotBlank() }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isFailure) MaterialTheme.colorScheme.error.copy(alpha = 0.62f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(headerOpenTodosModifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isFailure) LettaIcons.Error else LettaIcons.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.Inline),
                    tint = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Subagent ${if (isFailure) "failed" else "completed"}",
                    style = MaterialTheme.typography.chatBubbleSender,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                SubagentMetaChip(text = notification.status)
            }
            notification.summary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                notification.durationMs?.let(::formatToolExecutionTime)?.let { SubagentMetaChip(text = it) }
                notification.taskId?.let { SubagentMetaChip(text = it) }
            }
            if (canOpenSubagent) {
                Text(
                    text = "View conversation",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 32.dp)
                        .clickable { openSubagent() }
                        .padding(vertical = 8.dp),
                )
            }
            if (report != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            HapticEffects.segmentTick(haptic, view)
                            reportExpanded = !reportExpanded
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (reportExpanded) "Hide full report" else "Show full report",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = LettaIcons.ExpandMore,
                        contentDescription = if (reportExpanded) "Hide full report" else "Show full report",
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(if (reportExpanded) 180f else 0f),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(visible = reportExpanded) {
                    MarkdownText(
                        text = report,
                        textColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            notification.transcriptUri?.let { transcript ->
                Text(
                    text = "Transcript: $transcript",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SubagentDispatchCard(
    dispatch: UiSubagentDispatch,
    status: String?,
    executionTimeMs: Long?,
    modifier: Modifier = Modifier,
) {
    val opener = LocalSubagentTodoSheetOpener.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    var expanded by remember(dispatch.toolCallId, dispatch.prompt) { mutableStateOf(false) }
    val openTodosModifier = dispatch.toolCallId?.takeIf { it.isNotBlank() }?.let { callId ->
        Modifier.clickable {
            HapticEffects.segmentTick(haptic, view)
            opener(SubagentTodoSheetTarget(toolCallId = callId, description = dispatch.description))
        }
    } ?: Modifier
    Card(
        modifier = modifier.then(openTodosModifier),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = LettaIcons.Agent,
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.Inline),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Dispatched: ${dispatch.description}",
                    style = MaterialTheme.typography.chatBubbleSender,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (dispatch.runInBackground) {
                    SubagentMetaChip(text = "background")
                    Spacer(modifier = Modifier.width(4.dp))
                }
                SubagentMetaChip(text = dispatch.subagentType)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                status?.let { SubagentMetaChip(text = it) }
                executionTimeMs?.let(::formatToolExecutionTime)?.let { SubagentMetaChip(text = it) }
                dispatch.taskId?.let { SubagentMetaChip(text = it) }
            }
            if (dispatch.prompt.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            HapticEffects.segmentTick(haptic, view)
                            expanded = !expanded
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (expanded) "Hide prompt" else "Show prompt",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = LettaIcons.ExpandMore,
                        contentDescription = if (expanded) "Hide prompt" else "Show prompt",
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(if (expanded) 180f else 0f),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Text(
                        text = dispatch.prompt,
                        style = MaterialTheme.typography.listItemSupporting,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubagentMetaChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Parses a `<task-notification>` payload that arrives as a TOOL_RETURN result on
 * the Agent tool call itself (the in-card path), vs.
 * `MessageMapper.extractSubagentNotification` which parses the same format when
 * it arrives as a separate ASSISTANT message (the message-level path). Both
 * paths exist because the notification can surface either way depending on the
 * backend; `feature-chat` cannot depend on `core:data` mapper internals, so the
 * UI keeps a local parser. The `<task-notification>` schema is the shared source
 * of truth — if it changes, update BOTH this function and
 * `MessageMapper.extractSubagentNotification`. (CodeRabbit #343.)
 */
private fun parseTaskNotificationForToolCard(raw: String): UiSubagentNotification? {
    if (raw.indexOf("<task-notification", ignoreCase = true) < 0) return null
    fun tag(name: String): String? {
        return Regex("<$name(?:\\s[^>]*)?>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    fun lineAfter(marker: String): String? {
        val index = raw.indexOf(marker, ignoreCase = true)
        if (index < 0) return null
        val start = index + marker.length
        val end = raw.indexOf('\n', start).let { if (it < 0) raw.length else it }
        return raw.substring(start, end).trim().trimStart(':').trim().takeIf { it.isNotBlank() }
    }
    return UiSubagentNotification(
        status = tag("status") ?: "completed",
        summary = tag("summary"),
        result = tag("result"),
        usage = tag("usage"),
        transcriptUri = tag("transcript") ?: lineAfter("Full transcript at"),
        toolCallId = tag("tool_call_id") ?: tag("toolCallId"),
        taskId = tag("task_id") ?: tag("taskId"),
        subagentAgentId = tag("agent_id") ?: tag("agentId"),
    )
}


@Composable
internal fun ToolCallCard(
    toolCall: UiToolCall,
    approvalStateOverride: ToolApprovalState? = null,
    keepExpanded: Boolean = false,
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)? = null,
) {
    if (toolCall.name == "generate_image") {
        GeneratedImageToolCard(
            toolCall = toolCall,
            onAttachmentImageTap = onAttachmentImageTap,
        )
        return
    }
    val subagentDispatch = toolCall.subagentDispatch
    if (subagentDispatch != null) {
        SubagentDispatchCard(
            dispatch = subagentDispatch,
            status = toolCall.status,
            executionTimeMs = toolCall.executionTimeMs,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    // perf/frame-budget-audit: parseTaskNotificationForToolCard runs a chain of
    // regex extractions over the raw tool result. It was previously invoked on
    // every recompose of ToolCallCard (which recomposes per streamed token while
    // a tool card is the live message). Key it on the result so the parse only
    // runs when the result text actually changes.
    val subagentNotification = remember(toolCall.result) {
        toolCall.result?.let(::parseTaskNotificationForToolCard)
    }
    if (subagentNotification != null) {
        SubagentNotificationCard(
            notification = subagentNotification,
            toolCallId = toolCall.toolCallId,
            fallbackDescription = toolCall.name,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    val fontScale = LocalChatFontScale.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val reducedMotion = rememberReducedMotionEnabled()
    var expanded by remember { mutableStateOf(false) }
    val showDetails = keepExpanded || expanded
    val parentVisible = LocalToolCardBodyParentVisible.current
    val canRenderFullOutput = showDetails && parentVisible
    val deferHeavyOutput = toolCall.result != null && !canRenderFullOutput
    val renderStartedAtMs = System.currentTimeMillis()
    val display = remember(toolCall.name, toolCall.arguments) {
        ToolDisplayRegistry.resolve(toolCall.name, toolCall.arguments)
    }
    val argumentSummary = remember(toolCall.arguments) { summarizeToolArguments(toolCall.arguments) }
    val executionTimeText = remember(toolCall.executionTimeMs) { toolCall.executionTimeMs?.let(::formatToolExecutionTime) }
    val displayResult = remember(toolCall.result, deferHeavyOutput) {
        if (deferHeavyOutput) toolCall.result?.deferredToolResultPreview() else toolCall.result?.displayToolResult()
    }
    val resultPreview = remember(displayResult) { displayResult?.takeIf { it.isNotBlank() } }
    // Explicit-error-whitelist: only paint the Error icon / red color when
    // the server actually said "error". Treating `null`, "completed", or any
    // unrecognized value as error caused the long-running mis-labeling bug
    // tracked in `letta-mobile-o9ce`. See ToolReturnStatus for the full
    // empirical justification.
    val isError = ToolReturnStatus.isError(toolCall.status)
    val codeStyle = MaterialTheme.chatTypography.codeBlock
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
    val compactTitle = remember(toolCall.name, compactDetail, display.label, argumentSummary) {
        // letta-mobile-mtis: Prefer command-first summaries in Bash tool rows.
        if (toolCall.name == "Bash" && argumentSummary?.value != null) {
            val command = argumentSummary.value
            if (compactDetail != null && compactDetail.startsWith("Result: ")) {
                "$command - $compactDetail"
            } else if (compactDetail != null && compactDetail.startsWith("Error: ")) {
                "$command - $compactDetail"
            } else {
                command
            }
        } else {
            compactDetail?.let { "${toolCall.name} - $it" } ?: toolCall.name
        }
    }
    LaunchedEffect(toolCall.toolCallMotionKey(), showDetails, deferHeavyOutput, toolCall.result?.length) {
        if (Telemetry.isChatHotPathDebugEnabled()) {
            Telemetry.event(
                "ChatToolCard",
                "render.composed",
                "toolName" to toolCall.name,
                "hasResult" to (toolCall.result != null),
                "isExpanded" to showDetails,
                "deferredHeavyOutput" to deferHeavyOutput,
                "resultChars" to (toolCall.result?.length ?: 0),
                "effectDispatchDelayMs" to (System.currentTimeMillis() - renderStartedAtMs),
                level = Telemetry.Level.DEBUG,
            )
        }
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
                } else {
                    // perf/frame-budget-audit + reduced-motion contract: only
                    // run the infinite spin animation when reduced-motion is
                    // OFF. Under reduced motion show a static icon (no
                    // per-frame compositor invalidation), matching the
                    // disclosure/entrance animations which already honour it.
                    val angle = if (reducedMotion) {
                        0f
                    } else {
                        val infiniteTransition = rememberInfiniteTransition(label = "toolSpin")
                        val animated by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = LinearEasing),
                            ),
                            label = "toolSpinAngle",
                        )
                        animated
                    }
                    Icon(
                        imageVector = LettaIcons.Refresh,
                        contentDescription = "Running",
                        modifier = Modifier
                            .size(LettaIconSizing.Inline)
                            .graphicsLayer { rotationZ = angle },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // gestures keep the AnimatedContent wrapper mounted but switch to
            // instant transitions so the content tree does not disappear and
            // remount on finger-up.
            // letta-mobile-7kpxn (polish audit): reduced-motion users also get
            // the instant path so disclosure never animates when the OS
            // animation scale is 0 — matching the contract honoured elsewhere
            // in the tool-card lifecycle (enter / single<->group).
            val suppressLayoutAnimation = LocalChatIsPinching.current || reducedMotion
            AnimatedContent(
                targetState = showDetails,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    if (suppressLayoutAnimation) {
                        (ChatMotion.instantEnter() togetherWith ChatMotion.instantExit())
                            .using(SizeTransform(clip = true) { _, _ -> ChatMotion.instantSizeSpec })
                    } else {
                        // letta-mobile-vui8q: tool card disclosure now unfurls
                        // from the leading edge (horizontal + vertical expand
                        // + fade) instead of a plain vertical expand. Reads
                        // as 'the card is opening' rather than 'content
                        // appeared.'
                        (ChatMotion.unfurlEnter() togetherWith ChatMotion.unfurlExit())
                            .using(SizeTransform(clip = true) { _, _ -> ChatMotion.contentSizeSpec })
                    }
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

@Composable
private fun GeneratedImageToolCard(
    toolCall: UiToolCall,
    modifier: Modifier = Modifier,
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)? = null,
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val hasImage = toolCall.generatedImageAttachments.isNotEmpty()
    val isError = ToolReturnStatus.isError(toolCall.status)
    val prompt = remember(toolCall.arguments) { summarizeGenerateImagePrompt(toolCall.arguments) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f),
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.38f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when {
                        isError -> LettaIcons.Error
                        hasImage -> LettaIcons.CheckCircle
                        else -> LettaIcons.Refresh
                    },
                    contentDescription = when {
                        isError -> "Image generation failed"
                        hasImage -> "Generated image ready"
                        else -> "Generating image"
                    },
                    modifier = Modifier.size(LettaIconSizing.Inline),
                    tint = when {
                        isError -> MaterialTheme.colorScheme.error
                        hasImage -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onTertiaryContainer
                    },
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasImage) "Generated image" else "Generating image",
                        style = MaterialTheme.typography.chatBubbleSender,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    prompt?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.listItemSupporting,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                toolCall.executionTimeMs?.let { ms ->
                    ToolMetaChip(text = formatToolExecutionTime(ms))
                }
            }

            AnimatedContent(
                targetState = hasImage,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    if (reducedMotion) {
                        (ChatMotion.instantEnter() togetherWith ChatMotion.instantExit())
                            .using(SizeTransform(clip = true) { _, _ -> ChatMotion.instantSizeSpec })
                    } else {
                        (ChatMotion.expandEnter() togetherWith ChatMotion.expandExit())
                            .using(SizeTransform(clip = true) { _, _ -> ChatMotion.contentSizeSpec })
                    }
                },
                contentAlignment = Alignment.TopStart,
                label = "GeneratedImageToolStage",
            ) { ready ->
                if (ready) {
                    val generatedAttachments = remember(toolCall.generatedImageAttachments) {
                        toolCall.generatedImageAttachments.toImmutableList()
                    }
                    MessageAttachmentsGrid(
                        attachments = generatedAttachments,
                        modifier = Modifier.fillMaxWidth(),
                        onImageClick = onAttachmentImageTap?.let { cb ->
                            { index -> cb(generatedAttachments, index) }
                        },
                    )
                } else {
                    GeneratedImageShimmer(modifier = Modifier.fillMaxWidth())
                }
            }

            val errorText = toolCall.result?.takeIf { it.isNotBlank() }
            if (isError && errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun GeneratedImageShimmer(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(220.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(shimmerColor()),
        )
    }
}

private fun summarizeGenerateImagePrompt(arguments: String): String? {
    if (arguments.isBlank()) return null
    val prompt = extractJsonStringValue(arguments, "prompt")?.takeIf { it.isNotBlank() }
        ?: return arguments.take(96).let { if (arguments.length > 96) "$it…" else it }
    return prompt.take(120).let { if (prompt.length > 120) "$it…" else it }
}

private fun extractJsonStringValue(json: String, field: String): String? {
    val key = "\"$field\""
    val keyIndex = json.indexOf(key)
    if (keyIndex < 0) return null
    val colonIndex = json.indexOf(':', keyIndex + key.length)
    if (colonIndex < 0) return null
    val quoteStart = json.indexOf('"', colonIndex + 1)
    if (quoteStart < 0) return null
    val out = StringBuilder()
    var index = quoteStart + 1
    while (index < json.length) {
        when (val char = json[index]) {
            '\\' -> {
                val next = json.getOrNull(index + 1) ?: break
                out.append(
                    when (next) {
                        'n', 't', 'r' -> ' '
                        else -> next
                    }
                )
                index += 2
            }
            '"' -> break
            else -> {
                out.append(char)
                index += 1
            }
        }
    }
    return out.toString()
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
    val parentVisible = LocalToolCardBodyParentVisible.current
    val renderEligibility = remember(visible, parentVisible) {
        toolCardBodyRenderEligibility(
            expanded = visible,
            parentVisible = parentVisible,
        )
    }
    if (!renderEligibility.shouldRenderBody) return

    CompositionLocalProvider(
        LocalToolCardBodyRenderEligibility provides renderEligibility,
    ) {
        ToolCallExpandedBodyContentInner(
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

@Composable
private fun ToolCallExpandedBodyContentInner(
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
            // letta-mobile (toolcard-dedup): removed the "Tool: <name>" line
            // (the header already names the tool). Timing/detail kept below.
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
            // letta-mobile (toolcard-dedup): the raw-JSON "Arguments" block
            // was removed — it duplicated the concise header summary above
            // (e.g. the Bash command / file path). Header summary + Output
            // only; no repetition.
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            HapticEffects.segmentTick(haptic, view)
                            resultExpanded = !resultExpanded
                        },
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
    val parentVisible = LocalToolCardBodyParentVisible.current
    val renderEligibility = remember(parentVisible) {
        toolCardBodyRenderEligibility(
            expanded = true,
            parentVisible = parentVisible,
        )
    }
    if (!renderEligibility.shouldRenderBody) return

    val codeStyle = MaterialTheme.chatTypography.codeBlock
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    CompositionLocalProvider(
        LocalToolCardBodyRenderEligibility provides renderEligibility,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            // letta-mobile (toolcard-dedup): removed the "Tool: <name>" line
            // (header already names the tool). Timing/detail kept below.
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
            // letta-mobile (toolcard-dedup): removed the duplicate raw-JSON
            // "Arguments" block (same content as the header summary above).
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            HapticEffects.segmentTick(haptic, view)
                            resultExpanded = !resultExpanded
                        },
                )
            }
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
    // letta-mobile (toolcard-dedup): the "Result:" preview line was removed
    // — it duplicated the Output section below. Keep only the running-status
    // hint for in-flight calls that have no Output yet.
    if (resultPreview == null && toolCall.result == null) {
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
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)? = null,
) {
    val reducedMotion = rememberReducedMotionEnabled()
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
                    ToolCallEntrance(animate = shouldAnimateEntrance && !reducedMotion) {
                        CompactToolCallRow(
                            toolCall = toolCall,
                            approvalState = toolCall.approvalState(pendingApprovalToolCallIds),
                            onAttachmentImageTap = onAttachmentImageTap,
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
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)? = null,
) {
    val fontScale = LocalChatFontScale.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val reducedMotion = rememberReducedMotionEnabled()
    var expanded by remember(toolCall.toolCallMotionKey()) { mutableStateOf(false) }
    val parentVisible = LocalToolCardBodyParentVisible.current
    val canRenderFullOutput = expanded && parentVisible
    val deferHeavyOutput = toolCall.result != null && !canRenderFullOutput
    val renderStartedAtMs = System.currentTimeMillis()
    val display = remember(toolCall.name, toolCall.arguments) {
        ToolDisplayRegistry.resolve(toolCall.name, toolCall.arguments)
    }
    val argumentSummary = remember(toolCall.arguments) { summarizeToolArguments(toolCall.arguments) }
    val executionTimeText = remember(toolCall.executionTimeMs) { toolCall.executionTimeMs?.let(::formatToolExecutionTime) }
    val displayResult = remember(toolCall.result, deferHeavyOutput) {
        if (deferHeavyOutput) toolCall.result?.deferredToolResultPreview() else toolCall.result?.displayToolResult()
    }
    val resultPreview = remember(displayResult) { displayResult?.takeIf { it.isNotBlank() } }
    val summary = remember(toolCall.name, toolCall.arguments, displayResult, toolCall.status, display.detailLine) {
        compactToolCallSummary(toolCall, display.detailLine, displayResult)
    }
    val compactTitle = remember(toolCall.name, summary, argumentSummary) {
        // letta-mobile-mtis: Prefer command-first summaries in Bash compact tool rows.
        if (toolCall.name == "Bash" && argumentSummary?.value != null) {
            val command = argumentSummary.value
            if (summary.startsWith("Result: ") || summary.startsWith("Error: ")) {
                "$command - $summary"
            } else {
                command
            }
        } else {
            "${toolCall.name} - $summary"
        }
    }
    val isError = ToolReturnStatus.isError(toolCall.status)
    LaunchedEffect(toolCall.toolCallMotionKey(), expanded, deferHeavyOutput, toolCall.result?.length) {
        if (Telemetry.isChatHotPathDebugEnabled()) {
            Telemetry.event(
                "ChatToolCard",
                "compactRow.composed",
                "toolName" to toolCall.name,
                "hasResult" to (toolCall.result != null),
                "isExpanded" to expanded,
                "deferredHeavyOutput" to deferHeavyOutput,
                "resultChars" to (toolCall.result?.length ?: 0),
                "effectDispatchDelayMs" to (System.currentTimeMillis() - renderStartedAtMs),
                level = Telemetry.Level.DEBUG,
            )
        }
    }
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
                    contentDescription = if (approvalState == ToolApprovalState.Approved) {
                        "Approved, success"
                    } else {
                        "Success"
                    },
                    modifier = Modifier.size(LettaIconSizing.Inline),
                    tint = MaterialTheme.colorScheme.primary,
                )
                else -> {
                    // perf/frame-budget-audit + reduced-motion contract: skip
                    // the infinite spin under reduced motion (static icon, no
                    // per-frame compositor work).
                    val a = if (reducedMotion) {
                        0f
                    } else {
                        val t = rememberInfiniteTransition(label = "compactSpin")
                        val animated by t.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = LinearEasing),
                            ),
                            label = "compactSpinAngle",
                        )
                        animated
                    }
                    Icon(
                        imageVector = LettaIcons.Refresh,
                        contentDescription = "Running",
                        modifier = Modifier
                            .size(LettaIconSizing.Inline)
                            .graphicsLayer { rotationZ = a },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
        // letta-mobile-7kpxn (polish audit): the compact row's disclosure
        // previously used a bare `if (expanded)` cut, which popped the body in
        // with no transition while the single ToolCallCard unfurled smoothly —
        // an inconsistency in the lifecycle. Route the row through the SAME
        // unfurl AnimatedContent (with the instant path for pinch / reduced
        // motion) so single-card and grouped-row disclosure feel identical.
        val suppressLayoutAnimation = LocalChatIsPinching.current || reducedMotion
        AnimatedContent(
            targetState = expanded,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = {
                if (suppressLayoutAnimation) {
                    (ChatMotion.instantEnter() togetherWith ChatMotion.instantExit())
                        .using(SizeTransform(clip = true) { _, _ -> ChatMotion.instantSizeSpec })
                } else {
                    (ChatMotion.unfurlEnter() togetherWith ChatMotion.unfurlExit())
                        .using(SizeTransform(clip = true) { _, _ -> ChatMotion.contentSizeSpec })
                }
            },
            contentAlignment = Alignment.TopStart,
            label = "CompactToolCallRowExpanded",
        ) { expandedNow ->
            if (expandedNow) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 4.dp),
                ) {
                    if (toolCall.name == "generate_image") {
                        GeneratedImageToolCard(
                            toolCall = toolCall,
                            onAttachmentImageTap = onAttachmentImageTap,
                        )
                    } else {
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

internal fun String.deferredToolResultPreview(): String {
    val firstNonEmptyLine = lineSequence().firstOrNull { it.isNotBlank() } ?: ""
    val preview = firstNonEmptyLine.take(240).trim()
    return if (length > preview.length) "$preview…" else preview
}

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

/**
 * Plays a one-shot ENTER transition the first time a tool card appears in the
 * timeline (letta-mobile-7kpxn). The card fades + slides + expands from the top
 * edge via the canonical [ChatMotion.verticalEnter] ramp so a freshly-streamed
 * tool call eases into place instead of popping in.
 *
 * When [animate] is false (reduced-motion enabled, or the entrance has already
 * played for this card's stable key) the content renders immediately with no
 * transition — Compose's `AnimatedVisibility` with `visible = true` from the
 * first frame is skipped entirely so we never pay for an off-screen animation
 * pass. This keeps the reduced-motion contract and avoids replaying entrance
 * motion on lazy-list recycling.
 */
@Composable
internal fun ToolCallEntrance(
    animate: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!animate) {
        content()
        return
    }
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
