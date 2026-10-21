package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import com.letta.mobile.avatar.core.AttentionKey
import com.letta.mobile.avatar.core.AttentionKind
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.chat.projection.classifyToolCallState
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.compactLabel
import com.letta.mobile.data.messaging.displayLabel
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.extractSubagentNotification
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_copy_output
import com.letta.mobile.sharedui.resources.rows_tool_argument_line
import com.letta.mobile.sharedui.resources.rows_tool_done
import com.letta.mobile.sharedui.resources.rows_tool_executing
import com.letta.mobile.sharedui.resources.rows_tool_output
import com.letta.mobile.sharedui.resources.rows_tool_result_preview
import com.letta.mobile.sharedui.resources.rows_tool_status_duration
import com.letta.mobile.ui.chat.provenance.AgentMessageProvenanceMetadata
import com.letta.mobile.ui.chat.session.ChatMessageId
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.mascot.mascotAttention
import com.letta.mobile.ui.shell.pages.workspace.ToolCallFileLink
import com.letta.mobile.ui.theme.ChatRowMotion
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * One tool call, lifted from desktop's ToolCard.
 *
 * one step row on every host, built from the shared primitives
 * ([RunStepListRow]: a [com.letta.mobile.ui.components.LettaListRow] led by the run's
 * [RunStatusGlyph], the tool's name, a one-line preview of its command, and the shared
 * [com.letta.mobile.ui.components.DisclosureChevron] only when it has details). Success, failure and
 * running read in the glyph alone: no outlined card, no emoji, no trailing badge. Open, its details
 * (the command and the output) read in [ToolOutputBlock] under the row, flush with its title.
 *
 * Completed tools start collapsed; failures, running and image tools start expanded (a call parked
 * on an approval does not: its card shows the command). The disclosure is keyed on [disclosureKey],
 * not on the call's status, so a manual collapse survives the running -> completed refresh.
 *
 * Android behaviour added: a subagent dispatch renders as [SubagentDispatchCard], and
 * expanding a call whose result is a truncated preview asks the owner for the full body
 * (ChatActions.expandTruncatedToolResult).
 */
@Composable
internal fun ToolCard(
    toolCall: UiToolCall,
    disclosureKey: String,
    callbacks: ChatRowCallbacks,
    /** The call's state read with the run's approvals; classified from the call alone when null. */
    state: ToolTimelineState? = null,
) {
    val dispatch = toolCall.subagentDispatch
    if (dispatch != null) {
        SubagentDispatchCard(dispatch = dispatch, toolCall = toolCall, callbacks = callbacks)
        return
    }
    val classified = remember(toolCall) { classifyToolCallState(toolCall) }
    val view = ToolCardView(toolCall, state ?: classified, touchStyle())
    var expanded by remember(disclosureKey) { mutableStateOf(view.startsExpanded) }
    RequestFullResultOnExpand(toolCall, expanded, callbacks)
    val haptics = LocalHaptics.current
    val reducedMotion = LocalReducedMotion.current
    // A running step is where a working mascot looks (te3n1.2).
    val running = view.state == ToolTimelineState.Running
    Column(modifier = Modifier.fillMaxWidth().mascotAttention(AttentionKey("tool:$disclosureKey"), AttentionKind.TOOL, enabled = running)) {
        RunStepListRow(
            spec = toolStepRowSpec(view, callbacks),
            disclosure = RunStepDisclosure(expanded) {
                haptics.play(LettaHapticCue.SegmentTick)
                expanded = !expanded
            }.takeIf { view.hasDetails },
        )
        // letta-mobile-bglj6.1.19: the body unfurls from the leading edge (legacy
        // ChatToolCallCards / LettaMotion.unfurlEnter, 190 ms in, 130 ms out).
        AnimatedVisibility(
            visible = expanded && view.hasDetails,
            enter = ChatRowMotion(reducedMotion).unfurlEnter(),
            exit = ChatRowMotion(reducedMotion).unfurlExit(),
            label = "toolCardBody",
        ) {
            ToolCardBody(view, toolCall.isErrorStatus(), callbacks)
        }
    }
}

/** Fires once per expansion while the result is still a server-truncated preview. */
@Composable
private fun RequestFullResultOnExpand(toolCall: UiToolCall, expanded: Boolean, callbacks: ChatRowCallbacks) {
    val truncation = toolCall.resultTruncation ?: return
    LaunchedEffect(expanded, truncation.messageId) {
        if (expanded) callbacks.actions.expandTruncatedToolResult(ChatMessageId(truncation.messageId))
    }
}

/** One tool call as a step draws it: the call, its state, and whether the host is a phone. */
@Immutable
private class ToolCardView(val toolCall: UiToolCall, val state: ToolTimelineState, val touch: Boolean) {
    /** A phone heads a settled call's output with its outcome row instead of "Output". */
    val headsOutputWithOutcome: Boolean
        get() = touch && state !in UnsettledStates

    /** A call parked on an approval starts closed: the approval card under the steps shows its command. */
    val startsExpanded: Boolean
        get() = state != ToolTimelineState.AwaitingApproval && toolCall.shouldInitiallyExpand()

    /** Something to open: the command, the output, images, the run time or an agent message. */
    val hasDetails: Boolean
        get() = toolCall.arguments.isNotBlank() ||
            !toolCall.result.isNullOrBlank() ||
            toolCall.generatedImageAttachments.isNotEmpty() ||
            toolCall.executionTimeMs != null ||
            toolCall.agentMessageProvenance != null ||
            (touch && state == ToolTimelineState.Running)
}

private val UnsettledStates = setOf(ToolTimelineState.Running, ToolTimelineState.AwaitingApproval)

/**
 * What the step row says: the tool's name over a one-line preview of its command (or its result's
 * first short line); `agent_message_send` reads as an agent message (sender -> recipient) over its
 * delivery state.
 */
@Composable
private fun toolStepRowSpec(view: ToolCardView, callbacks: ChatRowCallbacks): RunStepRowSpec {
    val toolCall = view.toolCall
    val provenance = toolCall.agentMessageProvenance
    if (provenance != null) {
        val failed = provenance.deliveryState == AgentMessageDeliveryState.FAILED
        val stateLabel = provenance.deliveryState.displayLabel()
        return RunStepRowSpec(
            title = provenance.compactLabel(callbacks.resolveAgentName),
            subtitle = stateLabel,
            tone = if (failed) RunSummaryTone.Failed else view.state.glyphTone(),
            glyphLabel = stateLabel,
        )
    }
    // stepLabel parses the arguments JSON; remembered so a streamed token does not re-parse
    // every visible step.
    val label = remember(toolCall) { toolCall.stepLabel() }
    val preview = label.target.ifBlank { toolCall.stepSummary() }
    return RunStepRowSpec(
        title = toolCall.name,
        subtitle = preview.takeIf { it.isNotBlank() },
        tone = view.state.glyphTone(),
        glyphLabel = toolStateLabel(view.state),
    )
}

@Composable
private fun ToolCardBody(view: ToolCardView, isError: Boolean, callbacks: ChatRowCallbacks) {
    val toolCall = view.toolCall
    val provenance = toolCall.agentMessageProvenance
    Column(
        modifier = Modifier
            .testTag(ChatRowTestTags.TOOL_CARD_BODY)
            .padding(
                start = ChatRowSpacing.runStepDetailIndent,
                end = LettaDimens.Space.sm,
                top = LettaDimens.Space.hair,
                bottom = LettaDimens.Space.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        if (provenance != null) {
            val failed = provenance.deliveryState == AgentMessageDeliveryState.FAILED
            AgentMessageProvenanceMetadata(
                provenance,
                if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            )
        }
        if (view.touch && view.state == ToolTimelineState.Running) {
            LiveStatusText(
                text = stringResource(Res.string.rows_tool_executing, toolCall.name),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(ChatRowTestTags.TOOL_EXECUTING),
            )
        }
        toolCall.arguments.takeIf { it.isNotBlank() }?.let { ToolCommandBlock(it) }
        ToolCallFileLink(toolCall.arguments, toolName = toolCall.name)
        toolCall.result?.takeIf { it.isNotBlank() }?.let { ToolResultSection(view, it, isError, callbacks) }
        ToolGeneratedImages(toolCall, callbacks)
        ToolExecutionFooter(toolCall)
    }
}

/** An open step's command ("$ echo hello"), in the shared monospace [ToolOutputBlock]. */
@Composable
private fun ToolCommandBlock(arguments: String) {
    val primary = remember(arguments) { primaryToolArgument(arguments) }
    ToolOutputBlock(stringResource(Res.string.rows_tool_argument_line, primary))
}

/** A call's primary argument, monospace ("$ echo hello"): the Touch approval card's body. */
@Composable
internal fun ToolArgumentLine(arguments: String) {
    val primary = remember(arguments) { primaryToolArgument(arguments) }
    SelectionContainer {
        Text(
            text = stringResource(Res.string.rows_tool_argument_line, primary),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ToolResultSection(view: ToolCardView, result: String, isError: Boolean, callbacks: ChatRowCallbacks) {
    val toolCall = view.toolCall
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier.fillMaxWidth().hoverable(hoverSource),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The phone heads the output with its outcome (legacy ProjectedToolOutcomeLabel).
        if (view.headsOutputWithOutcome) {
            ToolOutcomeLabel(view.state)
        } else {
            Text(
                text = stringResource(Res.string.rows_tool_output),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        CopyIconButton(
            CopyAction(
                text = result,
                contentDescription = stringResource(Res.string.rows_copy_output),
                visible = hovered,
            ),
        )
    }
    val notification = remember(result) { extractSubagentNotification(result) }
    if (notification != null) {
        SubagentNotificationCard(notification, callbacks.openSubagent, toolCall.toolCallId)
    } else {
        ToolOutputBlock(result, isError = isError)
    }
    if (toolCall.resultTruncation != null) {
        Text(
            text = stringResource(Res.string.rows_tool_result_preview),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ChatRowTestTags.TOOL_RESULT_PREVIEW),
        )
    }
}

@Composable
private fun ToolGeneratedImages(toolCall: UiToolCall, callbacks: ChatRowCallbacks) {
    if (toolCall.generatedImageAttachments.isEmpty()) return
    val images = remember(toolCall.generatedImageAttachments) { toolCall.generatedImageAttachments.toImmutableList() }
    ChatImageAttachmentsGrid(tap = ImageTap(images, callbacks.onImageTap), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ToolExecutionFooter(toolCall: UiToolCall) {
    val ms = toolCall.executionTimeMs ?: return
    val status = toolCall.status?.replaceFirstChar { it.uppercase() } ?: stringResource(Res.string.rows_tool_done)
    Text(
        text = stringResource(Res.string.rows_tool_status_duration, status, ms),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
