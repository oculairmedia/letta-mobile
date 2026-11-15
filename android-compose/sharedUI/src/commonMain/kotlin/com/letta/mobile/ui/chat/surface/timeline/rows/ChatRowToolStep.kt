package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.avatar.core.gaze.AttentionKey
import com.letta.mobile.avatar.core.gaze.AttentionKind
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.chat.projection.classifyToolCallState
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolVerb
import com.letta.mobile.data.chat.tools.bundled.PlanModeToolPresenter
import com.letta.mobile.data.subagents.SubagentIdentity
import com.letta.mobile.data.subagents.subagentIdentityOf
import com.letta.mobile.data.chat.tools.ToolOutputStream
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.compactLabel
import com.letta.mobile.data.messaging.displayLabel
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.extractSubagentNotification
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_tool_awaiting_approval
import com.letta.mobile.sharedui.resources.rows_tool_exited
import com.letta.mobile.sharedui.resources.rows_tool_failed
import com.letta.mobile.sharedui.resources.rows_tool_result_preview
import com.letta.mobile.sharedui.resources.rows_tool_status_running
import com.letta.mobile.ui.chat.provenance.AgentMessageProvenanceMetadata
import com.letta.mobile.ui.chat.session.ChatMessageId
import com.letta.mobile.data.chat.tools.steps.ToolStepExpansions
import com.letta.mobile.ui.chat.subagents.ExternalAgentBadge
import com.letta.mobile.ui.chat.tools.LocalToolPresenters
import com.letta.mobile.ui.chat.tools.LocalToolStepExpansions
import com.letta.mobile.ui.chat.tools.title
import com.letta.mobile.ui.components.LineFrame
import com.letta.mobile.ui.components.LocalLineRail
import com.letta.mobile.ui.components.StatusGlyph
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.components.line.LineRail
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.mascot.mascotAttention
import com.letta.mobile.ui.theme.ChatRowMotion
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.ChatRowType
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
 * Entering or leaving plan mode draws as a thin divider ([PlanModeDivider]), still opening to its plan.
 */
@Composable
internal fun ToolStep(
    toolCall: UiToolCall,
    disclosureKey: String,
    callbacks: ChatRowCallbacks,
    /** The run's approval-aware state and measured width; standalone calls measure their own width. */
    presentation: Pair<ToolTimelineState, State<Int>?>? = null,
) {
    val resolved = presentation?.first ?: remember(toolCall) { classifyToolCallState(toolCall) }
    val expansions = LocalToolStepExpansions.current ?: remember { ToolStepExpansions() }
    val toggled by expansions.toggled.collectAsState()
    val isExpanded = expansions.isExpanded(disclosureKey, toolCall.shouldInitiallyExpand(), toggled)
    RequestFullResultOnExpand(toolCall, isExpanded, callbacks)
    val running = resolved == ToolTimelineState.Running
    // A running step is where a working mascot looks (te3n1.2).
    Column(modifier = Modifier.fillMaxWidth().mascotAttention(AttentionKey("tool:$disclosureKey"), AttentionKind.TOOL, enabled = running)) {
        val opens = stepOpens(toolCall, resolved)
        val disclosure = if (!opens) null else LineDisclosure(isExpanded) { expansions.toggle(disclosureKey) }
        ToolStepHeader(toolCall, resolved, callbacks, disclosure)
        AnimatedVisibility(
            visible = isExpanded && opens,
            enter = ChatRowMotion(LocalReducedMotion.current).expansionEnter(),
            exit = ChatRowMotion(LocalReducedMotion.current).expansionExit(),
            label = "toolStepInset",
        ) {
            ToolStepDetails(toolCall, callbacks, resolved, presentation?.second)
        }
    }
}

/** Whether a step opens at all: the presenter's cheap answer (a stopped task does not); a failed one always does. */
@Composable
private fun stepOpens(toolCall: UiToolCall, state: ToolTimelineState): Boolean =
    toolCall.hasDetails() && (rememberToolStepLine(toolCall).expandable || state.isFailure())

/** The step's line, or the thin divider entering or leaving plan mode draws instead (only leaving opens, to its plan). */
@Composable
private fun ToolStepHeader(toolCall: UiToolCall, state: ToolTimelineState, callbacks: ChatRowCallbacks, disclosure: LineDisclosure?) {
    if (PlanModeToolPresenter.isPlanModeChange(toolCall.name)) {
        val leaving = PlanModeToolPresenter.isLeaving(toolCall.name)
        PlanModeDivider(leaving = leaving, disclosure = disclosure.takeIf { leaving })
    } else {
        ToolStepLineRow(spec = stepLineSpec(toolCall, state, callbacks), disclosure = disclosure)
    }
}

/** What a step's line shows: the presenter's line, or an agent message's sender -> recipient over its delivery state. */
@Immutable
private class StepLineSpec(
    val title: String,
    val target: String,
    val note: StepNote?,
    val running: Boolean,
    /** Who a subagent dispatch went to, badged when it is an external coding agent (Claude Code, Codex). */
    val agent: SubagentIdentity? = null,
)

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
    // A watched task's line says how long it has run ("running 1m 12s"), ticking while it does; no other line ticks.
    val watching = running && rememberToolStepLine(toolCall).verb == ToolVerb.Watching
    val elapsed = if (watching) rememberElapsedSeconds(active = true, startedAtEpochMs = null).value * MILLIS_PER_SECOND else null
    val line = rememberToolStepLine(toolCall, elapsed ?: toolCall.executionTimeMs)
    val agent = toolCall.subagentDispatch?.let { subagentIdentityOf(it.subagentType, listOf(it.subagentAgentId)) }
    return StepLineSpec(line.title(), line.target, noteOf(line, state), running, agent)
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


/**
 * The step line: the shared [LineFrame] on the step rail, the whole row the toggle when there is
 * something to open. Its height never depends on its state, and the spinner's slot is kept when it
 * settles, so the words never move.
 */
@Composable
@NonRestartableComposable
private fun ToolStepLineRow(spec: StepLineSpec, disclosure: LineDisclosure?) {
    val runningLabel = stringResource(Res.string.rows_tool_status_running)
    LineFrame(
        rail = LineRail.Step,
        tag = ChatRowTestTags.TOOL_CARD_TOGGLE,
        disclosure = disclosure,
        glyph = { if (spec.running) StatusGlyph(StatusTone.Working, Modifier.testTag(ChatRowTestTags.TOOL_STATUS_GLYPH), runningLabel) },
    ) {
        StepLineWords(spec)
    }
}

@Composable
private fun RowScope.StepLineWords(spec: StepLineSpec) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(text = spec.title, style = ChatRowType.stepTitle, color = muted, maxLines = 1)
    Text(
        text = spec.target,
        style = ChatRowType.stepTitle,
        color = muted.copy(alpha = TARGET_ALPHA),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    spec.agent?.let { ExternalAgentBadge(it) }
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
@NonRestartableComposable
private fun ToolStepDetails(
    toolCall: UiToolCall,
    callbacks: ChatRowCallbacks,
    state: ToolTimelineState,
    runWidthPx: State<Int>?,
) {
    val live = state == ToolTimelineState.Running || toolCall.isStreaming()
    val failed = state.isFailure()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.TOOL_CARD_BODY)
            .runWidthPadding(
                ChatRowSpacing.runStepDetailIndent,
                runWidthPx,
                preserveIndent = state == ToolTimelineState.AwaitingApproval,
            )
            .padding(end = LettaDimens.Space.sm, bottom = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        toolCall.agentMessageProvenance?.let { provenance ->
            val failed = provenance.deliveryState == AgentMessageDeliveryState.FAILED
            AgentMessageProvenanceMetadata(provenance, if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
        }
        ToolStepInset(toolCall, callbacks, live, failed)
        ToolPreviewChips(toolCall, rememberToolStepLine(toolCall), callbacks)
        if (LocalToolImagesInline.current) ToolGeneratedImages(toolCall, callbacks)
    }
}

@Composable
private fun ToolStepInset(toolCall: UiToolCall, callbacks: ChatRowCallbacks, live: Boolean, failed: Boolean) {
    val notification = remember(toolCall.result) { toolCall.result?.let(::extractSubagentNotification) }
    if (notification != null) {
        // The details already sit flush with the step's title and inside its chevron's edge.
        CompositionLocalProvider(LocalLineRail provides LineRail.Nested) {
            SubagentNotificationCard(notification, callbacks.openSubagent, toolCall.toolCallId)
        }
        return
    }
    val presenters = LocalToolPresenters.current
    // A failed call shows what went wrong (muted red), unless its presenter read the failure itself
    // (a shell's exit code keeps its command over its stderr).
    val ownFailure = rememberToolStepLine(toolCall).failure != null
    val inset = remember(toolCall, presenters, failed, ownFailure) {
        failureInset(toolCall).takeIf { failed && !ownFailure } ?: presenters.inset(toolCall.facts())
    }
    inset?.let { ToolInsetView(it, live = live) }
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

/**
 * Whether a tool step draws its call's images inside its own details. A run block draws them
 * outside its foldable work instead (so a folded run still shows them) and turns this off.
 */
internal val LocalToolImagesInline = staticCompositionLocalOf { true }

@Composable
internal fun ToolGeneratedImages(toolCall: UiToolCall, callbacks: ChatRowCallbacks) {
    if (toolCall.generatedImageAttachments.isEmpty()) return
    val images = remember(toolCall.generatedImageAttachments) { toolCall.generatedImageAttachments.toImmutableList() }
    ChatImageAttachmentsGrid(tap = ImageTap(images, callbacks.onImageTap), modifier = Modifier.fillMaxWidth())
}

private const val TARGET_ALPHA = 0.8f
private const val MILLIS_PER_SECOND = 1_000L

/** A call whose output may still grow: no terminal status yet, and not read back settled from the ledger. */
private fun UiToolCall.isStreaming(): Boolean = status == null && !settled

/** What a failed call said, as stderr in an inset of its own; null when it said nothing. */
private fun failureInset(toolCall: UiToolCall): ToolInset? =
    toolCall.result?.takeIf { it.isNotBlank() }?.let { error ->
        ToolInset.Command(workingDirectory = null, command = "", output = listOf(ToolOutputStream(ToolOutputStream.Kind.Stderr, error)))
    }
