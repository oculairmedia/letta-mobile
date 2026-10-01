package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Terminal
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.RunActivityProjection
import com.letta.mobile.data.chat.projection.RunActivityState
import com.letta.mobile.data.chat.projection.projectRunActivity
import com.letta.mobile.data.chat.projection.projectRunContent
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_collapse
import com.letta.mobile.sharedui.resources.rows_expand
import com.letta.mobile.sharedui.resources.rows_list_separator
import com.letta.mobile.sharedui.resources.rows_ran_steps
import com.letta.mobile.sharedui.resources.rows_run_failures
import com.letta.mobile.sharedui.resources.rows_run_for_duration
import com.letta.mobile.sharedui.resources.rows_run_thought
import com.letta.mobile.sharedui.resources.rows_run_tools
import com.letta.mobile.sharedui.resources.rows_run_worked
import com.letta.mobile.sharedui.resources.rows_run_working
import com.letta.mobile.sharedui.resources.rows_state_collapsed
import com.letta.mobile.sharedui.resources.rows_state_expanded
import com.letta.mobile.sharedui.resources.rows_step_done
import com.letta.mobile.sharedui.resources.rows_step_failed
import com.letta.mobile.sharedui.resources.rows_step_running
import com.letta.mobile.sharedui.resources.rows_thought
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: a run of assistant messages sharing a run id (desktop's
 * DesktopRunBlock over the shared projectRunContent): reasoning, the run's tool steps,
 * narration, then generated UI / approvals / images per message.
 *
 * Android behaviour added: the run activity disclosure (Working / Worked for 12s · 3 tools)
 * whose collapse is owner state (ChatActions.toggleRunCollapsed + collapsedRunIds). A
 * collapsed, settled run shows only its final narration; active work never collapses, and
 * a pending approval stays visible either way.
 */
@Composable
internal fun RunBlockRow(
    item: ChatRenderItem.RunBlock,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    val messages = remember(item.messages) { item.messages.map { it.first }.toImmutableList() }
    val projection = remember(messages) { projectRunContent(messages) }
    val active = context.itemState.isStreaming &&
        context.streamingMessageId?.let { item.containsMessageId(it) } == true
    val activity = remember(messages, active) { projectRunActivity(messages, active) } ?: return
    val collapsible = messages.size > 1
    val collapsed = collapsible && !activity.isActive && item.runId in context.itemState.collapsedRunIds
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.RUN_BLOCK),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        RunActivityHeader(
            activity = activity,
            collapsed = collapsed,
            onToggle = if (collapsible) ({ callbacks.actions.toggleRunCollapsed(item.runId) }) else null,
        )
        if (collapsed) {
            projection.narration.lastOrNull()?.let { NarrationText(it, context) }
            messages.forEach { message ->
                message.approvalRequest?.let { ApprovalRequestCard(it, context, callbacks) }
            }
        } else {
            projection.reasoning.forEach { ReasoningRow(it, context, callbacks) }
            if (projection.toolCalls.isNotEmpty()) {
                RunStepsCard(item.runId, projection.toolCalls.toImmutableList(), callbacks)
            }
            projection.narration.forEach { NarrationText(it, context) }
            messages.forEach { RunMessageExtras(it, context, callbacks) }
        }
    }
}

@Composable
private fun NarrationText(message: UiMessage, context: ChatRowContext) {
    AgentText(AgentTextParams(message.content, message.isError, context.isStreaming(message)))
}

@Composable
private fun RunMessageExtras(message: UiMessage, context: ChatRowContext, callbacks: ChatRowCallbacks) {
    message.subagentNotification?.let { SubagentNotificationCard(it, callbacks.openSubagent) }
    message.generatedUi?.let { GeneratedUiCard(it, context, callbacks) }
    message.approvalRequest?.let { ApprovalRequestCard(it, context, callbacks) }
    message.approvalResponse?.let { ApprovalResponseCard(it) }
    MessageImages(message, callbacks)
}

@Composable
private fun RunActivityHeader(
    activity: RunActivityProjection,
    collapsed: Boolean,
    onToggle: (() -> Unit)?,
) {
    val summary = runActivitySummary(activity)
    val disclosure = stringResource(if (collapsed) Res.string.rows_state_collapsed else Res.string.rows_state_expanded)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.RUN_HEADER)
            .then(
                if (onToggle != null) {
                    Modifier.clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = disclosure }
                } else {
                    Modifier
                },
            )
            .padding(vertical = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onToggle != null) {
            DisclosureChevron(
                expanded = !collapsed,
                compact = true,
                contentDescription = stringResource(if (collapsed) Res.string.rows_expand else Res.string.rows_collapse),
            )
        }
    }
}

@Composable
private fun runActivitySummary(activity: RunActivityProjection): String {
    val state = when (activity.state) {
        RunActivityState.Working -> stringResource(Res.string.rows_run_working)
        RunActivityState.Thought -> stringResource(Res.string.rows_run_thought)
        RunActivityState.Worked -> stringResource(Res.string.rows_run_worked)
    }
    val head = activity.durationMs?.let { stringResource(Res.string.rows_run_for_duration, state, formatDuration(it)) } ?: state
    val parts = buildList {
        add(head)
        if (activity.toolCount > 0) {
            add(pluralStringResource(Res.plurals.rows_run_tools, activity.toolCount, activity.toolCount))
        }
        if (activity.failureCount > 0) add(stringResource(Res.string.rows_run_failures, activity.failureCount))
    }
    return parts.joinToString(stringResource(Res.string.rows_list_separator))
}

/**
 * Reasoning behind a quiet "Thought" toggle. Open/closed is owner state
 * (expandedReasoningMessageIds), so it survives the row leaving and re-entering the list.
 */
@Composable
internal fun ReasoningRow(message: UiMessage, context: ChatRowContext, callbacks: ChatRowCallbacks) {
    val open = message.id in context.itemState.expandedReasoningMessageIds
    val disclosure = stringResource(if (open) Res.string.rows_state_expanded else Res.string.rows_state_collapsed)
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Row(
            modifier = Modifier
                .testTag(ChatRowTestTags.REASONING_TOGGLE)
                .clickable(role = Role.Button) { callbacks.actions.toggleReasoningExpanded(message.id) }
                .semantics { stateDescription = disclosure },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Text(
                text = stringResource(Res.string.rows_thought),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DisclosureChevron(expanded = open)
        }
        if (open) {
            SelectionContainer {
                Text(
                    text = message.content.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = LettaDimens.Space.md),
                )
            }
        }
    }
}

/**
 * The run's tool activity as a chrome-less log: a quiet "Ran N steps" toggle with one
 * [ToolCard] per call underneath, each led by its step status.
 */
@Composable
private fun RunStepsCard(runId: String, toolCalls: ImmutableList<UiToolCall>, callbacks: ChatRowCallbacks) {
    var expanded by remember(runId) { mutableStateOf(true) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ChatRowTestTags.RUN_STEPS_TOGGLE)
                .clickable { expanded = !expanded }
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
                text = pluralStringResource(Res.plurals.rows_ran_steps, toolCalls.size, toolCalls.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            DisclosureChevron(
                expanded = expanded,
                compact = true,
                contentDescription = stringResource(if (expanded) Res.string.rows_collapse else Res.string.rows_expand),
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = LettaDimens.Space.md, top = LettaDimens.Space.hair)) {
                toolCalls.forEachIndexed { index, toolCall ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                        Box(modifier = Modifier.padding(top = LettaDimens.Space.sm)) { StepStatusCircle(toolCall.stepState()) }
                        Box(modifier = Modifier.weight(1f)) {
                            ToolCard(toolCall, toolCall.disclosureKey().ifBlank { "$runId:$index" }, callbacks)
                        }
                    }
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
