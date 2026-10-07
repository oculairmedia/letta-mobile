package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Terminal
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.chat.projection.classifyToolCallState
import com.letta.mobile.data.chat.projection.extractSubagentNotification
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.AgentMessageProvenance
import com.letta.mobile.data.messaging.compactLabel
import com.letta.mobile.data.messaging.displayLabel
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_agent_message_state
import com.letta.mobile.sharedui.resources.rows_collapse
import com.letta.mobile.sharedui.resources.rows_copy_output
import com.letta.mobile.sharedui.resources.rows_copy_tool_call
import com.letta.mobile.sharedui.resources.rows_expand
import com.letta.mobile.sharedui.resources.rows_state_collapsed
import com.letta.mobile.sharedui.resources.rows_state_expanded
import com.letta.mobile.sharedui.resources.rows_tool_argument_line
import com.letta.mobile.sharedui.resources.rows_tool_done
import com.letta.mobile.sharedui.resources.rows_tool_executing
import com.letta.mobile.sharedui.resources.rows_tool_output
import com.letta.mobile.sharedui.resources.rows_tool_result_preview
import com.letta.mobile.sharedui.resources.rows_tool_status_duration
import com.letta.mobile.ui.chat.provenance.AgentMessageProvenanceMetadata
import com.letta.mobile.ui.chat.render.ToolEmojis
import com.letta.mobile.ui.chat.session.ChatMessageId
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.ChatRowType
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: one tool call, lifted from desktop's ToolCard.
 *
 * Chrome-less when it succeeds (a one-line activity row); failures keep an outlined card so
 * they are noticed. Completed tools start collapsed; failures, running and image tools start
 * expanded. The disclosure is keyed on [disclosureKey], not on the call's status, so a manual
 * collapse survives the running -> completed refresh.
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
) {
    val dispatch = toolCall.subagentDispatch
    if (dispatch != null) {
        SubagentDispatchCard(dispatch = dispatch, toolCall = toolCall, callbacks = callbacks)
        return
    }
    var expanded by remember(disclosureKey) { mutableStateOf(toolCall.shouldInitiallyExpand()) }
    RequestFullResultOnExpand(toolCall, expanded, callbacks)
    val isError = toolCall.isErrorStatus()
    // letta-mobile-bglj6.1.23: the phone draws the legacy status row (emoji, status glyph, 48dp
    // header, outcome row); its glyph marks a failure, so the row needs no outlined card.
    val touch = touchStyle()
    val view = ToolCardView(toolCall, remember(toolCall) { classifyToolCallState(toolCall) }, touch)
    val body: @Composable () -> Unit = {
        Column {
            ToolCardHeader(view, expanded, callbacks) { expanded = !expanded }
            if (expanded) ToolCardBody(view, isError, callbacks)
        }
    }
    if (isError && !touch) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(LettaDimens.Radius.sm),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.error),
            content = body,
        )
    } else {
        Box(modifier = Modifier.fillMaxWidth()) { body() }
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

/** One tool call as a card draws it: the call, its classified state, and whether the host is a phone. */
@Immutable
private class ToolCardView(val toolCall: UiToolCall, val state: ToolTimelineState, val touch: Boolean)

@Composable
private fun ToolCardHeader(
    view: ToolCardView,
    expanded: Boolean,
    callbacks: ChatRowCallbacks,
    onToggle: () -> Unit,
) {
    val provenance = view.toolCall.agentMessageProvenance
    when {
        provenance != null -> ToolCardProvenanceHeader(provenance, expanded, callbacks, onToggle)
        view.touch -> ToolCardTouchHeader(view, expanded, onToggle)
        else -> ToolCardGenericHeader(view.toolCall, expanded, onToggle)
    }
}

/**
 * letta-mobile-bglj6.1.23: the legacy status row (CollapsibleStatusRow + ChatToolCallCards'
 * collapsed line): a 48dp header with the tool's emoji, its name in the section title, the
 * call's gist while closed, its status (duration, "Failed", ...) right-aligned while open, and
 * the status glyph: turning while it runs, then a check, a warning or an error.
 */
@Composable
private fun ToolCardTouchHeader(view: ToolCardView, expanded: Boolean, onToggle: () -> Unit) {
    val toolCall = view.toolCall
    val label = remember(toolCall) { toolCall.stepLabel() }
    val labelText = label.text()
    val collapsedSummary = labelText.takeUnless { it == toolCall.name } ?: toolCall.stepSummary()
    val disclosureState = stringResource(if (expanded) Res.string.rows_state_expanded else Res.string.rows_state_collapsed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.TOOL_CARD_TOGGLE)
            .clickable(onClick = onToggle, role = Role.Button)
            .semantics { stateDescription = disclosureState }
            .heightIn(min = ChatRowDimens.toolHeaderMinHeight)
            .padding(horizontal = LettaDimens.Space.xs, vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(
            text = ToolEmojis.forTool(toolCall.name),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(ChatRowTestTags.TOOL_EMOJI),
        )
        Text(
            text = toolCall.name,
            style = ChatRowType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val trailing = if (expanded) toolStatusLabel(view.state, toolCall.executionTimeMs) else collapsedSummary.takeIf { it.isNotBlank() }
        Text(
            text = trailing.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = if (expanded) toolStatusColor(view.state) else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (expanded) TextAlign.End else TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
        ToolStatusGlyph(view.state)
        ToolDisclosureIcon(expanded)
    }
}

/** `agent_message_send` reads as an agent message (sender -> recipient), not a tool name. */
@Composable
private fun ToolCardProvenanceHeader(
    provenance: AgentMessageProvenance,
    expanded: Boolean,
    callbacks: ChatRowCallbacks,
    onToggle: () -> Unit,
) {
    val isFailed = provenance.deliveryState == AgentMessageDeliveryState.FAILED
    val tint = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    val stateColor = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val label = provenance.compactLabel(callbacks.resolveAgentName)
    val stateLabel = provenance.deliveryState.displayLabel()
    val description = stringResource(Res.string.rows_agent_message_state, label, stateLabel.lowercase())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.TOOL_CARD_TOGGLE)
            .clickable(onClick = onToggle)
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = Lucide.ArrowUpRight,
            contentDescription = null,
            modifier = Modifier.size(LettaDimens.Control.iconSm),
            tint = tint,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = stateLabel, style = MaterialTheme.typography.labelSmall, color = stateColor)
        ToolDisclosureIcon(expanded)
    }
}

@Composable
private fun ToolDisclosureIcon(expanded: Boolean) {
    DisclosureChevron(
        expanded = expanded,
        compact = true,
        contentDescription = stringResource(if (expanded) Res.string.rows_collapse else Res.string.rows_expand),
    )
}

@Composable
private fun ToolCardGenericHeader(
    toolCall: UiToolCall,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    // stepLabel parses the arguments JSON; remembered so a streamed token does not re-parse
    // every visible card.
    val label = remember(toolCall) { toolCall.stepLabel() }
    val labelText = label.text()
    val stepSummary = toolCall.stepSummary()
    val collapsedSummary = labelText.takeUnless { it == toolCall.name } ?: stepSummary
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val disclosureState = stringResource(if (expanded) Res.string.rows_state_expanded else Res.string.rows_state_collapsed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.TOOL_CARD_TOGGLE)
            .clickable(onClick = onToggle, role = Role.Button)
            .semantics { stateDescription = disclosureState }
            .hoverable(hoverSource)
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = Lucide.Terminal,
            contentDescription = null,
            modifier = Modifier.size(LettaDimens.Control.iconSm),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = toolCall.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!expanded && collapsedSummary.isNotBlank()) {
            Text(
                text = collapsedSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        ToolFailureBadge(toolCall.status)
        if (expanded) {
            Spacer(Modifier.weight(1f))
            // Only offered while open: a copy hit target in every collapsed row would undo
            // the compact activity-log rhythm.
            CopyIconButton(
                CopyAction(
                    text = toolCall.copyPayload(),
                    contentDescription = stringResource(Res.string.rows_copy_tool_call),
                    visible = hovered,
                ),
            )
        }
        ToolDisclosureIcon(expanded)
    }
}

@Composable
private fun ToolCardBody(view: ToolCardView, isError: Boolean, callbacks: ChatRowCallbacks) {
    val toolCall = view.toolCall
    val provenance = toolCall.agentMessageProvenance
    Column(
        modifier = Modifier
            .testTag(ChatRowTestTags.TOOL_CARD_BODY)
            .padding(
                start = LettaDimens.Space.xxl,
                end = LettaDimens.Space.md,
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
        toolCall.arguments.takeIf { it.isNotBlank() }?.let { ToolArgumentLine(it) }
        toolCall.result?.takeIf { it.isNotBlank() }?.let { ToolResultSection(view, it, isError, callbacks) }
        ToolGeneratedImages(toolCall, callbacks)
        ToolExecutionFooter(toolCall)
    }
}

@Composable
private fun ToolArgumentLine(arguments: String) {
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
        if (view.touch && view.state != ToolTimelineState.Running && view.state != ToolTimelineState.AwaitingApproval) {
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
