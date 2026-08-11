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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.scaledBy
import com.letta.mobile.ui.theme.sectionTitle
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.toImmutableList
import com.letta.mobile.ui.chat.render.ToolDisplayInfo
import com.letta.mobile.ui.chat.render.ToolDisplayRegistry
import com.letta.mobile.feature.chat.render.LocalToolCardBodyParentVisible
import com.letta.mobile.feature.chat.render.LocalToolCardBodyRenderEligibility
import com.letta.mobile.feature.chat.render.LocalTruncatedToolResultResolver
import com.letta.mobile.feature.chat.render.ToolOutputRenderer
import com.letta.mobile.feature.chat.render.toolCardBodyRenderEligibility
import com.letta.mobile.feature.chat.subagent.LocalSubagentTodoSheetOpener
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetTarget
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.shimmerColor
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.letta.mobile.ui.preview.LettaPreviewFrame

internal val LocalChatShouldDeferHeavyToolCards = compositionLocalOf { false }

/**
 * letta-mobile-nfaks: this is the NON-run tool card path (a tool-call message
 * that never got grouped into a RunBlock step). It routes through the same
 * projected-timeline presentation ([ProjectedMessageToolCalls]) that run-grouped
 * calls use, so a conversation never mixes two different tool card styles.
 */
@Composable
internal fun MessageToolCalls(
    toolCalls: kotlinx.collections.immutable.ImmutableList<UiToolCall>,
    modifier: Modifier = Modifier,
    messageId: String? = null,
    animateEntrance: Boolean = false,
    approvalRequest: UiApprovalRequest? = null,
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)? = null,
) {
    ProjectedMessageToolCalls(
        toolCalls = toolCalls,
        modifier = modifier,
        messageId = messageId,
        animateEntrance = animateEntrance,
        approvalRequest = approvalRequest,
        onAttachmentImageTap = onAttachmentImageTap,
    )
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
        if (!effectiveToolCallId.isNullOrBlank()) {
            HapticEffects.segmentTick(haptic, view)
            opener(
                SubagentTodoSheetTarget(
                    toolCallId = effectiveToolCallId,
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
internal fun SubagentDispatchCard(
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
internal fun parseTaskNotificationForToolCard(raw: String): UiSubagentNotification? {
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
    RequestFullToolResultOnExpand(toolCall = toolCall, expanded = showDetails)
    val parentVisible = LocalToolCardBodyParentVisible.current
    val deferHeavyCards = LocalChatShouldDeferHeavyToolCards.current
    val canRenderFullOutput = showDetails && parentVisible && !deferHeavyCards
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
    val isWarning = toolCall.status == "warning"
    val isComplete = toolCall.result != null || toolCall.status == "success" || toolCall.status == "warning"
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

    // Dropped the Card's background fill + outline border — chrome enough on its own.
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
            } else if (isWarning) {
                Icon(
                    imageVector = LettaIcons.Warning,
                    contentDescription = "Warning",
                    modifier = Modifier.size(LettaIconSizing.Inline),
                    tint = MaterialTheme.customColors.warningTextColor,
                )
            } else if (isComplete) {
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

@Composable
internal fun GeneratedImageToolCard(
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
    isWarning: Boolean = false,
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
                            isWarning = isWarning,
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
                // letta-mobile (toolcard-result-expand): was keyed on
                // toolCall.result, which mutates on every streamed chunk
                // and reset resultExpanded to false mid-stream, collapsing
                // the user's expanded card while output was still arriving.
                // Key on toolCallId (stable across the lifetime of one tool
                // call, changes only when a different tool starts) and use
                // rememberSaveable so a new tool starts collapsed but a
                // config change (rotation, process death) preserves the
                // user's current expand/collapse choice.
                var resultExpanded by rememberSaveable(toolCall.toolCallId) { mutableStateOf(false) }
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
                            text = if (resultExpanded) "collapse" else "$lineCount line${if (lineCount == 1) "" else "s"}",
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
    isWarning: Boolean = false,
    fontScale: Float,
) {
    argumentSummary?.let { summary ->
        Spacer(modifier = Modifier.height(6.dp))
        ToolSummaryLine(
            label = summary.label,
            value = summary.value,
            fontScale = fontScale,
            isError = isError,
            isWarning = isWarning,
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

internal fun String.displayToolResult(): String = ToolOutputParser.sanitizeResultFieldText(this)

internal fun String.deferredToolResultPreview(): String {
    val firstNonEmptyLine = lineSequence().firstOrNull { it.isNotBlank() } ?: ""
    val preview = firstNonEmptyLine.take(240).trim()
    return if (length > preview.length) "$preview…" else preview
}


internal fun UiToolCall.toolCallMotionKey(): String = buildString {
    append(toolCallId ?: name)
    append('|')
    append(arguments.hashCode())
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
    isWarning: Boolean = false,
    maxLines: Int = 1,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.sectionTitle.scaledBy(fontScale),
            color = if (isError) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            } else if (isWarning) {
                MaterialTheme.customColors.warningTextColor.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
            },
        )
        Text(
            text = value,
            style = MaterialTheme.typography.listItemSupporting.scaledBy(fontScale),
            color = if (isError) MaterialTheme.colorScheme.error else if (isWarning) MaterialTheme.customColors.warningTextColor else MaterialTheme.colorScheme.onSurfaceVariant,
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
            when (val next = json[i + 1]) {
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

/**
 * letta-mobile-fe51r (P2b pointer diet): when a tool card whose result is a
 * server-projected preview is expanded, ask the screen-provided resolver to
 * fetch the full body. The fetch resolves through the timeline (the card
 * recomposes with the full result and a cleared [UiToolCall.resultTruncation]
 * once it lands), so this fires at most once per messageId per expansion.
 */
@Composable
internal fun RequestFullToolResultOnExpand(toolCall: UiToolCall, expanded: Boolean) {
    val truncation = toolCall.resultTruncation ?: return
    val resolver = LocalTruncatedToolResultResolver.current ?: return
    LaunchedEffect(expanded, truncation.messageId, resolver) {
        if (expanded) {
            resolver.requestFullToolResult(truncation.messageId)
        }
    }
}

// region Previews

@PreviewLightDark
@Composable
private fun ToolApprovalChipRequestingInputPreview() {
    LettaPreviewFrame {
        ToolApprovalChip(state = ToolApprovalState.RequestingInput)
    }
}

@PreviewLightDark
@Composable
private fun ToolApprovalChipApprovedPreview() {
    LettaPreviewFrame {
        ToolApprovalChip(state = ToolApprovalState.Approved)
    }
}

@PreviewLightDark
@Composable
private fun ToolApprovalChipRejectedPreview() {
    LettaPreviewFrame {
        ToolApprovalChip(state = ToolApprovalState.Rejected)
    }
}

@PreviewLightDark
@Composable
private fun ToolMetaChipPreview() {
    LettaPreviewFrame {
        ToolMetaChip(text = "12.4s")
    }
}

// endregion
