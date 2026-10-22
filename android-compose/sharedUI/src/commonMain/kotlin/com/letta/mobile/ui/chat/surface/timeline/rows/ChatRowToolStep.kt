package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.avatar.core.AttentionKey
import com.letta.mobile.avatar.core.AttentionKind
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.chat.projection.classifyToolCallState
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.compactLabel
import com.letta.mobile.data.messaging.displayLabel
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.extractSubagentNotification
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_collapse
import com.letta.mobile.sharedui.resources.rows_tool_awaiting_approval
import com.letta.mobile.sharedui.resources.rows_expand
import com.letta.mobile.sharedui.resources.rows_state_collapsed
import com.letta.mobile.sharedui.resources.rows_state_expanded
import com.letta.mobile.sharedui.resources.rows_tool_exited
import com.letta.mobile.sharedui.resources.rows_tool_failed
import com.letta.mobile.sharedui.resources.rows_tool_result_preview
import com.letta.mobile.sharedui.resources.rows_tool_status_running
import com.letta.mobile.ui.chat.provenance.AgentMessageProvenanceMetadata
import com.letta.mobile.ui.chat.session.ChatMessageId
import com.letta.mobile.ui.chat.tools.LocalToolPresenters
import com.letta.mobile.ui.chat.tools.title
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.mascot.mascotAttention
import com.letta.mobile.ui.shell.pages.workspace.ToolCallFileLink
import com.letta.mobile.ui.theme.ChatRowMotion
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.customColors
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * One tool call as one muted, verb-first step line ("Ran  ./gradlew test  ›"), the same on every
 * host: the presenter's verb and target, a small spinner only while it runs, a muted amber
 * "exited 2" only when it failed, and a chevron when it has something to open. No card, no badge.
 * Opened, its [ToolInsetView] unfolds under the line (height animated, snapping under reduced
 * motion), with the call's file chips and images. The inset is built only while it is open.
 *
 * A step starts collapsed (one that generated images starts open); the disclosure is keyed on the
 * call's [disclosureKey], so it survives the running -> settled refresh and scrolling away.
 * A subagent dispatch draws as its [SubagentDispatchCard].
 */
@Composable
internal fun ToolStep(
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
    val resolved = state ?: remember(toolCall) { classifyToolCallState(toolCall) }
    var expanded by rememberSaveable(disclosureKey) { mutableStateOf(toolCall.shouldInitiallyExpand()) }
    RequestFullResultOnExpand(toolCall, expanded, callbacks)
    val haptics = LocalHaptics.current
    val running = resolved == ToolTimelineState.Running
    // A running step is where a working mascot looks (te3n1.2).
    Column(modifier = Modifier.fillMaxWidth().mascotAttention(AttentionKey("tool:$disclosureKey"), AttentionKind.TOOL, enabled = running)) {
        ToolStepLineRow(
            spec = stepLineSpec(toolCall, resolved, callbacks),
            disclosure = if (!toolCall.hasDetails()) null else RunStepDisclosure(expanded) {
                haptics.play(LettaHapticCue.SegmentTick)
                expanded = !expanded
            },
        )
        AnimatedVisibility(
            visible = expanded && toolCall.hasDetails(),
            enter = ChatRowMotion(LocalReducedMotion.current).expansionEnter(),
            exit = ChatRowMotion(LocalReducedMotion.current).expansionExit(),
            label = "toolStepInset",
        ) {
            ToolStepDetails(toolCall, callbacks)
        }
    }
}

/** What a step's line shows: the presenter's line, or an agent message's sender -> recipient over its delivery state. */
@Immutable
private class StepLineSpec(val title: String, val target: String, val note: StepNote?, val running: Boolean)

/** A step line's muted note: a failure in amber, or that it waits on the person. */
@Immutable
private class StepNote(val text: String, val waiting: Boolean = false)

@Composable
private fun stepLineSpec(toolCall: UiToolCall, state: ToolTimelineState, callbacks: ChatRowCallbacks): StepLineSpec {
    val running = state == ToolTimelineState.Running
    val provenance = toolCall.agentMessageProvenance
    if (provenance != null) {
        val failed = provenance.deliveryState == AgentMessageDeliveryState.FAILED
        return StepLineSpec(
            title = provenance.compactLabel(callbacks.resolveAgentName),
            target = provenance.deliveryState.displayLabel(),
            note = StepNote(stringResource(Res.string.rows_tool_failed)).takeIf { failed },
            running = running,
        )
    }
    val line = rememberToolStepLine(toolCall)
    return StepLineSpec(line.title(), line.target, noteOf(line, state), running)
}

/** "exited 2" for a non-zero exit, "failed" for a failure naming no code, "awaiting approval" while parked; else nothing. */
@Composable
private fun noteOf(line: ToolStepLine, state: ToolTimelineState): StepNote? {
    val code = line.failure?.exitCode
    return when {
        code != null -> StepNote(stringResource(Res.string.rows_tool_exited, code))
        state == ToolTimelineState.AwaitingApproval -> StepNote(stringResource(Res.string.rows_tool_awaiting_approval), waiting = true)
        state.isFailure() || line.failure != null -> StepNote(stringResource(Res.string.rows_tool_failed))
        else -> null
    }
}

private fun ToolTimelineState.isFailure(): Boolean = this == ToolTimelineState.Failed || this == ToolTimelineState.Rejected

/**
 * The step line: the whole row is the toggle when there is something to open. Its height never
 * depends on its state, and the spinner's slot is kept when it settles, so the words never move.
 */
@Composable
private fun ToolStepLineRow(spec: StepLineSpec, disclosure: RunStepDisclosure?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatRowSpacing.toolStepMinHeight)
            .testTag(ChatRowTestTags.TOOL_CARD_TOGGLE)
            .then(if (disclosure != null) Modifier.stepToggle(disclosure) else Modifier.semantics { disabled() })
            .padding(horizontal = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Box(Modifier.size(ChatRowSpacing.runGlyphSize)) {
            if (spec.running) {
                val label = stringResource(Res.string.rows_tool_status_running)
                RunStatusGlyph(RunSummaryTone.Working, Modifier.testTag(ChatRowTestTags.TOOL_STATUS_GLYPH), label)
            }
        }
        StepLineWords(spec)
        disclosure?.let { DisclosureChevron(expanded = it.expanded, contentDescription = stringResource(it.actionLabel())) }
    }
}

@Composable
private fun RowScope.StepLineWords(spec: StepLineSpec) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(text = spec.title, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1)
    Text(
        text = spec.target,
        style = MaterialTheme.typography.bodyMedium,
        color = muted.copy(alpha = TARGET_ALPHA),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    spec.note?.let { note ->
        Text(
            text = note.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (note.waiting) MaterialTheme.colorScheme.secondary else warningInk(),
            maxLines = 1,
            modifier = Modifier.testTag(ChatRowTestTags.TOOL_STEP_NOTE),
        )
    }
}

@Composable
private fun Modifier.stepToggle(disclosure: RunStepDisclosure): Modifier {
    val stateText = stringResource(if (disclosure.expanded) Res.string.rows_state_expanded else Res.string.rows_state_collapsed)
    val action = stringResource(disclosure.actionLabel())
    return clickable(role = Role.Button, onClickLabel = action, onClick = disclosure.onToggle)
        .semantics { stateDescription = stateText }
}

private fun RunStepDisclosure.actionLabel() = if (expanded) Res.string.rows_collapse else Res.string.rows_expand

/** Something to open: the command or arguments, the output, images or an agent message's details. */
private fun UiToolCall.hasDetails(): Boolean =
    arguments.isNotBlank() || !result.isNullOrBlank() || generatedImageAttachments.isNotEmpty() || agentMessageProvenance != null


/** The theme's muted warning ink (amber), or tertiary where the host's theme sets none. */
@Composable
private fun warningInk(): Color {
    val warning = MaterialTheme.customColors.warningTextColor
    return if (warning == Color.Unspecified) MaterialTheme.colorScheme.tertiary else warning
}

/** An open step: its agent-message metadata, its inset (built now, not before), its file chips and its images. */
@Composable
private fun ToolStepDetails(toolCall: UiToolCall, callbacks: ChatRowCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.TOOL_CARD_BODY)
            .padding(start = ChatRowSpacing.runStepDetailIndent, end = LettaDimens.Space.sm, bottom = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        toolCall.agentMessageProvenance?.let { provenance ->
            val failed = provenance.deliveryState == AgentMessageDeliveryState.FAILED
            AgentMessageProvenanceMetadata(provenance, if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
        }
        ToolStepInset(toolCall, callbacks)
        rememberToolStepLine(toolCall).previews.firstOrNull()?.let { ToolCallFileLink(it) }
        ToolGeneratedImages(toolCall, callbacks)
    }
}

@Composable
private fun ToolStepInset(toolCall: UiToolCall, callbacks: ChatRowCallbacks) {
    val notification = remember(toolCall.result) { toolCall.result?.let(::extractSubagentNotification) }
    if (notification != null) {
        SubagentNotificationCard(notification, callbacks.openSubagent, toolCall.toolCallId)
        return
    }
    val presenters = LocalToolPresenters.current
    val inset = remember(toolCall, presenters) { presenters.inset(toolCall.facts()) }
    inset?.let { ToolInsetView(it) }
    if (toolCall.resultTruncation != null) {
        Text(
            text = stringResource(Res.string.rows_tool_result_preview),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ChatRowTestTags.TOOL_RESULT_PREVIEW),
        )
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

@Composable
private fun ToolGeneratedImages(toolCall: UiToolCall, callbacks: ChatRowCallbacks) {
    if (toolCall.generatedImageAttachments.isEmpty()) return
    val images = remember(toolCall.generatedImageAttachments) { toolCall.generatedImageAttachments.toImmutableList() }
    ChatImageAttachmentsGrid(tap = ImageTap(images, callbacks.onImageTap), modifier = Modifier.fillMaxWidth())
}

private const val TARGET_ALPHA = 0.8f
