package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.chat.projection.classifyToolCallState
import com.letta.mobile.data.chat.projection.parseTimestampEpochMillis
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_command_count
import com.letta.mobile.sharedui.resources.rows_step_done
import com.letta.mobile.sharedui.resources.rows_step_failed
import com.letta.mobile.sharedui.resources.rows_step_running
import com.letta.mobile.sharedui.resources.rows_tool_run_approval
import com.letta.mobile.sharedui.resources.rows_tool_run_default_name
import com.letta.mobile.sharedui.resources.rows_tool_run_failed
import com.letta.mobile.sharedui.resources.rows_tool_run_hide
import com.letta.mobile.sharedui.resources.rows_tool_run_open
import com.letta.mobile.sharedui.resources.rows_tool_run_ran
import com.letta.mobile.sharedui.resources.rows_tool_run_running
import com.letta.mobile.sharedui.resources.rows_tool_run_show
import com.letta.mobile.sharedui.resources.rows_tool_run_state
import com.letta.mobile.sharedui.resources.rows_tool_run_summary
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.components.ChevronIndication
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.ChatRowType
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * letta-mobile-bglj6.1: a run's (or a message's) tool calls as ONE quiet summary line, as the
 * Android timeline draws them (feature-chat ProjectedToolTimelineGroupCard / ToolRunSummaryRow):
 * "Ran 2 commands", "2 ran - 1 failed" in the error tint, "Running Bash - 1 command - 0:12" while
 * one runs. Tapping it shows each call as a full [ToolCard] (command, output, copy): in a bottom
 * sheet on a touch host ([ChatToolDetails.Sheet]), or expanded in place under the line, as a
 * disclosure, on a pointer host ([ChatToolDetails.Inline]). Approvals that wait on the user show
 * their controls under the line.
 *
 * letta-mobile-bglj6.1.11: when the line opens its run, the run's plain [title] ("Worked for 1m 7s")
 * leads it, so a run reads as ONE line with ONE chevron. Only the tool part of the line is the
 * disclosure; the title never takes a click.
 */
@Composable
internal fun ToolRunGroup(
    calls: ToolRunCalls,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
    modifier: Modifier = Modifier,
) {
    val title = calls.title
    val toolCalls = calls.toolCalls
    val approvals = calls.approvals
    if (toolCalls.isEmpty()) return
    // Saved by the run's first call (it stays first as the run adds more), so a disclosure the
    // person opened survives scrolling it away.
    var detailsOpen by rememberSaveable(toolCalls.first().disclosureKey()) { mutableStateOf(false) }
    val inline = context.toolDetails == ChatToolDetails.Inline
    val summary = remember(toolCalls, approvals) { summarizeToolRun(toolCalls, approvals) }
    val startedAtEpochMs = remember(calls.startedAtTimestamp) { calls.startedAtTimestamp?.let(::parseTimestampEpochMillis) }
    val haptics = LocalHaptics.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            title?.let { RunSummaryLead(it) }
            ToolRunSummaryRow(
                line = ToolRunLine(summary, startedAtEpochMs),
                disclosure = toolRunDisclosure(inline, detailsOpen),
                onClick = {
                    haptics.play(LettaHapticCue.SegmentTick)
                    detailsOpen = if (inline) !detailsOpen else true
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (inline) ToolRunInlineCards(visible = detailsOpen, toolCalls, callbacks)
        approvals.filter { it.requiresUserInput() }.forEach { ApprovalRequestCard(it, context, callbacks) }
    }
    if (detailsOpen && !inline) {
        ToolRunDetailsSheet(toolCalls, ToolRunLine(summary, startedAtEpochMs), callbacks) { detailsOpen = false }
    }
}

/**
 * The calls one summary line folds: every call, the approvals among them, and when the first of
 * them started (the running clock's origin; null or unparseable counts from first shown).
 */
@Immutable
internal data class ToolRunCalls(
    val toolCalls: ImmutableList<UiToolCall>,
    val approvals: ImmutableList<UiApprovalRequest> = persistentListOf(),
    val startedAtTimestamp: String? = null,
    /** The run's plain label, when the line opens its run (bglj6.1.11). */
    val title: RunSummaryTitle? = null,
)

private fun toolRunDisclosure(inline: Boolean, detailsOpen: Boolean): ToolRunDisclosure {
    return if (inline) ToolRunDisclosure.Inline(expanded = detailsOpen) else ToolRunDisclosure.Sheet
}

/** The pointer host's in-place disclosure: the calls in full under the summary line. */
@Composable
private fun ColumnScope.ToolRunInlineCards(
    visible: Boolean,
    toolCalls: ImmutableList<UiToolCall>,
    callbacks: ChatRowCallbacks,
) {
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
    ) {
        ToolRunCards(
            toolCalls = toolCalls,
            callbacks = callbacks,
            modifier = Modifier.testTag(ChatRowTestTags.TOOL_RUN_INLINE).padding(bottom = LettaDimens.Space.xs),
        )
    }
}

/** What the summary line (and the sheet titled after it) says: its words, approvals and running clock included. */
@Immutable
private class ToolRunLine(val summary: ToolRunSummary, val startedAtEpochMs: Long?)

/** What the summary line's chevron promises: a sheet, or an in-place disclosure and its state. */
@Immutable
private sealed interface ToolRunDisclosure {
    data object Sheet : ToolRunDisclosure

    data class Inline(val expanded: Boolean) : ToolRunDisclosure
}

/** What one summary line says. */
@Immutable
internal data class ToolRunSummary(
    val toolCount: Int,
    val failureCount: Int,
    val awaitingApprovalCount: Int,
    val running: Boolean,
    val activeToolName: String?,
)

internal fun summarizeToolRun(toolCalls: List<UiToolCall>, approvals: List<UiApprovalRequest> = emptyList()): ToolRunSummary {
    val states = toolCalls.map { call ->
        call to classifyToolCallState(call, approvals.firstOrNull { request -> request.toolCalls.any { it.toolCallId == call.toolCallId } })
    }
    return ToolRunSummary(
        toolCount = toolCalls.size,
        failureCount = states.count { (_, state) -> state == ToolTimelineState.Failed || state == ToolTimelineState.Rejected },
        awaitingApprovalCount = states.count { (_, state) -> state == ToolTimelineState.AwaitingApproval },
        running = states.any { (_, state) -> state == ToolTimelineState.Running },
        activeToolName = states.lastOrNull { (_, state) -> state == ToolTimelineState.Running }?.first?.name,
    )
}

@Composable
private fun ToolRunSummaryRow(
    line: ToolRunLine,
    disclosure: ToolRunDisclosure,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = line.summary
    val elapsed by rememberElapsedSeconds(summary.running, line.startedAtEpochMs)
    val click = rememberQuietClick()
    val color = toolRunColor(summary, click.lifted)
    val description = stringResource(Res.string.rows_tool_run_summary)
    val state = stringResource(Res.string.rows_tool_run_state, summary.toolCount, summary.failureCount, summary.awaitingApprovalCount)
    val expanded = (disclosure as? ToolRunDisclosure.Inline)?.expanded == true
    val chevronLabel = stringResource(
        when (disclosure) {
            ToolRunDisclosure.Sheet -> Res.string.rows_tool_run_open
            is ToolRunDisclosure.Inline -> if (expanded) Res.string.rows_tool_run_hide else Res.string.rows_tool_run_show
        },
    )
    Row(
        modifier = modifier
            .testTag(ChatRowTestTags.TOOL_RUN_SUMMARY)
            .semantics {
                contentDescription = description
                stateDescription = state
            }
            // No hover block: the label lifts instead (the Android line has no inset).
            .quietClickable(click, onClick = onClick)
            // Shares the prose's leading edge: no horizontal inset; the run label's floor and beat.
            .heightIn(min = ChatRowSpacing.summaryLineMinHeight)
            .padding(vertical = ChatRowSpacing.summaryLineVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(
            text = toolRunLabel(summary, elapsed),
            style = ChatRowType.summaryLine,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        DisclosureChevron(
            expanded = expanded,
            indicates = if (disclosure == ToolRunDisclosure.Sheet) ChevronIndication.Sheet else ChevronIndication.Expansion,
            contentDescription = chevronLabel,
        )
    }
}

/** The error tint on a failure, the secondary one while an approval waits, else the quiet label lifting under a pointer. */
@Composable
private fun toolRunColor(summary: ToolRunSummary, lifted: Boolean): Color {
    return when {
        summary.failureCount > 0 -> MaterialTheme.colorScheme.error
        summary.awaitingApprovalCount > 0 -> MaterialTheme.colorScheme.secondary
        lifted -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun toolRunLabel(summary: ToolRunSummary, elapsedSeconds: Long): String {
    val commands = pluralStringResource(Res.plurals.rows_command_count, summary.toolCount, summary.toolCount)
    return when {
        summary.awaitingApprovalCount > 0 -> stringResource(Res.string.rows_tool_run_approval, commands)
        summary.running -> stringResource(
            Res.string.rows_tool_run_running,
            summary.activeToolName.orEmpty().ifBlank { stringResource(Res.string.rows_tool_run_default_name) },
            commands,
            formatElapsedClock(elapsedSeconds),
        )
        summary.failureCount > 0 -> stringResource(Res.string.rows_tool_run_failed, summary.toolCount, summary.failureCount)
        else -> stringResource(Res.string.rows_tool_run_ran, commands)
    }
}

/** Seconds since [startedAtEpochMs] (or since first shown), ticking once a second while [active]. */
@Composable
internal fun rememberElapsedSeconds(active: Boolean, startedAtEpochMs: Long?): State<Long> =
    produceState(0L, active, startedAtEpochMs) {
        value = elapsedSecondsSince(startedAtEpochMs) ?: 0L
        while (active) {
            delay(ELAPSED_TICK_MILLIS)
            value = elapsedSecondsSince(startedAtEpochMs) ?: (value + 1L)
        }
    }

private fun elapsedSecondsSince(startedAtEpochMs: Long?): Long? =
    startedAtEpochMs?.let { ((Clock.System.now().toEpochMilliseconds() - it).coerceAtLeast(0L)) / ELAPSED_TICK_MILLIS }

private const val ELAPSED_TICK_MILLIS = 1_000L

/** The run's calls in full, one [ToolCard] each, led by its step status. */
@Composable
private fun ToolRunDetailsSheet(
    toolCalls: ImmutableList<UiToolCall>,
    heading: ToolRunLine,
    callbacks: ChatRowCallbacks,
    onDismiss: () -> Unit,
) {
    val elapsed by rememberElapsedSeconds(heading.summary.running, heading.startedAtEpochMs)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(ChatRowTestTags.TOOL_RUN_DETAILS),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = LettaDimens.Space.xl, end = LettaDimens.Space.xl, bottom = LettaDimens.Space.xxl),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Text(
                text = toolRunLabel(heading.summary, elapsed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ToolRunCards(toolCalls, callbacks)
        }
    }
}

/** Each call as a full [ToolCard], led by its step status: the sheet's body, or the inline disclosure's. */
@Composable
private fun ToolRunCards(toolCalls: ImmutableList<UiToolCall>, callbacks: ChatRowCallbacks, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        toolCalls.forEachIndexed { index, call ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                Box(modifier = Modifier.padding(top = LettaDimens.Space.sm)) { StepStatusCircle(call.stepState()) }
                Box(modifier = Modifier.weight(1f)) {
                    ToolCard(call, call.disclosureKey().ifBlank { "sheet:$index" }, callbacks)
                }
            }
        }
    }
}

@Composable
internal fun StepStatusCircle(state: StepState) {
    val accent = MaterialTheme.colorScheme.primary
    val size = Modifier.size(LettaDimens.Control.iconSm)
    when (state) {
        StepState.Done -> Box(size.background(accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = LettaIcons.Check,
                contentDescription = stringResource(Res.string.rows_step_done),
                modifier = size,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        StepState.Running -> {
            val label = stringResource(Res.string.rows_step_running)
            Box(size.border(LettaDimens.Stroke.hairline, accent, CircleShape).semantics { contentDescription = label })
        }
        StepState.Error -> Box(size.background(MaterialTheme.colorScheme.error, CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = LettaIcons.Close,
                contentDescription = stringResource(Res.string.rows_step_failed),
                modifier = size,
                tint = MaterialTheme.colorScheme.onError,
            )
        }
    }
}
