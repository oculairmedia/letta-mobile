package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.chat.projection.TaskActivity
import com.letta.mobile.data.chat.projection.activity
import com.letta.mobile.data.model.CommandStream
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.extractSubagentNotification
import com.letta.mobile.data.model.isCommand
import com.letta.mobile.data.model.parseBackgroundCommandReport
import com.letta.mobile.data.subagents.SubagentIdentity
import com.letta.mobile.data.subagents.subagentIdentityOf
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_activity_command
import com.letta.mobile.sharedui.resources.rows_activity_subagent
import com.letta.mobile.sharedui.resources.rows_activity_task
import com.letta.mobile.sharedui.resources.rows_completed
import com.letta.mobile.sharedui.resources.rows_copy_transcript_path
import com.letta.mobile.sharedui.resources.rows_dispatch_done
import com.letta.mobile.sharedui.resources.rows_dispatch_failed
import com.letta.mobile.sharedui.resources.rows_dispatch_running
import com.letta.mobile.sharedui.resources.rows_hide_details
import com.letta.mobile.sharedui.resources.rows_hide_full_report
import com.letta.mobile.sharedui.resources.rows_hide_prompt
import com.letta.mobile.sharedui.resources.rows_hide_transcript
import com.letta.mobile.sharedui.resources.rows_notification_headline
import com.letta.mobile.sharedui.resources.rows_show_details
import com.letta.mobile.sharedui.resources.rows_show_full_report
import com.letta.mobile.sharedui.resources.rows_show_prompt
import com.letta.mobile.sharedui.resources.rows_show_transcript
import com.letta.mobile.sharedui.resources.rows_status_unknown
import com.letta.mobile.sharedui.resources.rows_subagent_background
import com.letta.mobile.sharedui.resources.rows_subagent_dispatched
import com.letta.mobile.sharedui.resources.rows_task_id
import com.letta.mobile.sharedui.resources.rows_tool_argument_line
import com.letta.mobile.sharedui.resources.rows_tool_output
import com.letta.mobile.sharedui.resources.rows_transcript
import com.letta.mobile.sharedui.resources.rows_view_conversation
import com.letta.mobile.ui.chat.subagents.ExternalAgentBadge
import com.letta.mobile.ui.components.ChevronEmphasis
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// letta-mobile-bglj6.1: Android's SubagentDispatchCard / SubagentNotificationCard
// (ChatToolCallCards.kt), without haptics, opening the subagent through the host
// (ChatRowCallbacks.openSubagent) instead of LocalSubagentTodoSheetOpener.

/** The outbound half: an Agent tool call that dispatched a subagent. */
@Composable
internal fun SubagentDispatchCard(
    dispatch: UiSubagentDispatch,
    toolCall: UiToolCall,
    callbacks: ChatRowCallbacks,
) {
    val open = callbacks.openSubagent
    val callId = dispatch.toolCallId?.takeIf { it.isNotBlank() }
    val headerModifier = if (open != null && callId != null) {
        Modifier.clickable {
            open(ChatSubagentTarget(callId, dispatch.description, dispatch.subagentAgentId))
        }
    } else {
        Modifier
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.SUBAGENT_DISPATCH).padding(vertical = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        DispatchHeader(dispatch, headerModifier)
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            toolCall.status?.let { MetaChip(stringResource(dispatchStatus(it))) }
            toolCall.executionTimeMs?.let { MetaChip(formatDuration(it)) }
            dispatch.taskId?.let { MetaChip(it) }
        }
        if (dispatch.prompt.isNotBlank()) DispatchPrompt(dispatch)
        val notification = remember(toolCall.result) { toolCall.result?.let(::extractSubagentNotification) }
        notification?.let { SubagentNotificationCard(it, open, dispatch.toolCallId) }
    }
}

@Composable
private fun DispatchHeader(dispatch: UiSubagentDispatch, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = LettaIcons.Agent,
            contentDescription = null,
            modifier = Modifier.size(LettaDimens.Control.icon),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = stringResource(Res.string.rows_subagent_dispatched, dispatch.description),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (dispatch.runInBackground) MetaChip(stringResource(Res.string.rows_subagent_background))
        // letta-mobile-zxk2n.8 (G40): an external coding agent's dispatch names it.
        ExternalAgentBadge(subagentIdentityOf(dispatch.subagentType, listOf(dispatch.subagentAgentId)))
        MetaChip(dispatch.subagentType)
    }
}

@Composable
private fun DispatchPrompt(dispatch: UiSubagentDispatch) {
    var expanded by remember(dispatch.toolCallId, dispatch.prompt) { mutableStateOf(false) }
    val label = stringResource(if (expanded) Res.string.rows_hide_prompt else Res.string.rows_show_prompt)
    val haptics = LocalHaptics.current
    DisclosureLink(label = label, expanded = expanded) {
        haptics.play(LettaHapticCue.SegmentTick)
        expanded = !expanded
    }
    if (expanded) {
        Text(
            text = dispatch.prompt,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = ChatRowDimens.dispatchPromptMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun dispatchStatus(toolStatus: String): StringResource {
    val status = toolStatus.lowercase()
    return when {
        status in DISPATCH_DONE_STATUSES -> Res.string.rows_dispatch_done
        status in DISPATCH_FAILED_STATUSES -> Res.string.rows_dispatch_failed
        else -> Res.string.rows_dispatch_running
    }
}

private val DISPATCH_DONE_STATUSES = setOf("success", "succeeded", "completed", "complete", "done")
private val DISPATCH_FAILED_STATUSES = setOf("error", "failed")

/** The return half: a subagent's `<task-notification>`. */
@Immutable
private data class NotificationModel(
    val notification: UiSubagentNotification,
    val target: ChatSubagentTarget?,
    val report: String?,
) {
    val status: String = notification.status.trim().lowercase()
    val isFailure: Boolean = status in NOTIFICATION_FAILURE_STATUSES

    /**  (G40): a report from an external coding agent names it. */
    val identity: SubagentIdentity = subagentIdentityOf(null, listOf(notification.subagentAgentId))

    // The protocol names `completed` as its successful terminal state; older producers said
    // `success`. Never compact a status that is not positively successful.
    val isSuccessfulCompletion: Boolean = status in NOTIFICATION_SUCCESS_STATUSES

    /** A report, or a transcript to point at: what the details disclosure has to show. */
    fun hasDetails(): Boolean {
        return report != null || notification.transcriptUri != null
    }
}

private val NOTIFICATION_FAILURE_STATUSES = setOf("failed", "error")
private val NOTIFICATION_SUCCESS_STATUSES = setOf("completed", "success")

/**
 * A completed subagent contributes one compact row; its report, metadata and actions stay
 * behind a disclosure. In-flight, failed or unknown statuses keep their evidence visible.
 */
@Composable
internal fun SubagentNotificationCard(
    notification: UiSubagentNotification,
    openSubagent: ((ChatSubagentTarget) -> Unit)?,
    toolCallId: String? = null,
) {
    val callId = toolCallId ?: notification.toolCallId ?: notification.taskId
    val isSubagent = notification.activity() == TaskActivity.Subagent
    val fallback = stringResource(Res.string.rows_activity_subagent)
    val model = NotificationModel(
        notification = notification,
        target = callId?.takeIf { isSubagent && it.isNotBlank() }
            ?.let { ChatSubagentTarget(it, notification.summary ?: fallback, notification.subagentAgentId) },
        report = notification.result?.takeIf { it.isNotBlank() },
    )
    var detailsExpanded by remember(notification.taskId, callId, notification.result) { mutableStateOf(false) }
    val actions = NotificationActions(
        onOpen = model.target?.let { target -> openSubagent?.let { open -> { open(target) } } },
        onToggleDetails = { detailsExpanded = !detailsExpanded },
    )
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.SUBAGENT_NOTIFICATION).padding(vertical = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        if (model.isSuccessfulCompletion) {
            CompletedSummaryRow(model, detailsExpanded, actions.onToggleDetails)
            if (detailsExpanded) NotificationBody(model, detailsExpanded, actions)
        } else {
            NotificationHeader(model, actions.onOpen)
            NotificationBody(model, detailsExpanded, actions)
        }
    }
}

@Immutable
private class NotificationActions(
    val onOpen: (() -> Unit)?,
    val onToggleDetails: () -> Unit,
)

private fun UiSubagentNotification.activityLabel(): StringResource = when (activity()) {
    TaskActivity.Command -> Res.string.rows_activity_command
    TaskActivity.Subagent -> Res.string.rows_activity_subagent
    TaskActivity.Task -> Res.string.rows_activity_task
}

@Composable
private fun CompletedSummaryRow(model: NotificationModel, expanded: Boolean, onToggle: () -> Unit) {
    val summary = model.notification.summary ?: stringResource(Res.string.rows_activity_subagent)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY)
            .clickable(onClick = onToggle)
            .padding(vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = LettaIcons.CheckCircle,
            contentDescription = stringResource(Res.string.rows_completed),
            modifier = Modifier.size(LettaDimens.Control.icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ExternalAgentBadge(model.identity)
        DisclosureChevron(expanded = expanded, contentDescription = detailsLabel(model, expanded))
    }
}

@Composable
private fun NotificationHeader(model: NotificationModel, onOpen: (() -> Unit)?) {
    val notification = model.notification
    val headline = notification.status.trim().ifBlank { stringResource(Res.string.rows_status_unknown) }
    val color = if (model.isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = if (model.isFailure) LettaIcons.Error else LettaIcons.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(LettaDimens.Control.icon),
            tint = if (model.isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                Res.string.rows_notification_headline,
                stringResource(notification.activityLabel()),
                headline,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.weight(1f),
        )
        ExternalAgentBadge(model.identity)
    }
}

@Composable
private fun NotificationBody(model: NotificationModel, expanded: Boolean, actions: NotificationActions) {
    val notification = model.notification
    // letta-mobile-2a7yv: a completed task's summary already heads it in the compact row.
    notification.summary?.takeUnless { model.isSuccessfulCompletion }?.let { summary ->
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // One humanized duration: the usage block's `duration_ms` line is the same number again.
    val usage = remember(notification.usage) { notification.usage?.withoutDurationLine() }
    if (usage != null || notification.durationMs != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            usage?.let { MetaChip(it) }
            notification.durationMs?.let { MetaChip(formatDuration(it)) }
        }
    }
    NotificationActionRow(model, expanded, actions)
    if (expanded && model.hasDetails()) {
        if (model.report != null) NotificationReport(model)
        NotificationFooter(notification)
    }
}

private fun String.withoutDurationLine(): String? =
    lines().filterNot { it.trim().startsWith("duration_ms", ignoreCase = true) }
        .joinToString("\n").trim().takeIf { it.isNotBlank() }

/** The task id (an internal handle, so quiet) and a transcript path the reader can copy. */
@Composable
private fun NotificationFooter(notification: UiSubagentNotification) {
    notification.taskId?.let { taskId ->
        Text(
            text = stringResource(Res.string.rows_task_id, taskId),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(ChatRowAlpha.deliveryTime),
        )
    }
    notification.transcriptUri?.let { transcript ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.rows_transcript, transcript),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ChatRowTestTags.NOTIFICATION_TRANSCRIPT),
                )
            }
            CopyIconButton(
                CopyAction(text = transcript, contentDescription = stringResource(Res.string.rows_copy_transcript_path)),
            )
        }
    }
}

/**
 * A background command's report is its stdout — preformatted text, never
 * prose. Fed through markdown, shell like `T=$(grep …); A=$(cat …)` paired its `$`s into TeX
 * math and column-aligned output ran together into one paragraph, so command reports take the
 * tool-output block (monospace, line-preserving, horizontally scrollable). A subagent's report
 * is the agent's own prose and keeps the markdown paint.
 */
@Composable
private fun NotificationReport(model: NotificationModel) {
    if (model.notification.isCommand()) {
        CommandReport(model)
    } else {
        SharedMarkdownText(text = model.report.orEmpty())
    }
}

/**
 * A shell's report in the tool card's own pieces. The command and each output
 * tail get their own [ToolOutputBlock] (monospace, newlines kept, horizontally scrollable), and
 * stderr takes the error inset whatever the exit code, so a traceback never reads as output.
 */
@Composable
private fun CommandReport(model: NotificationModel) {
    val parsed = remember(model.report) { parseBackgroundCommandReport(model.report.orEmpty()) }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.COMMAND_REPORT),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        parsed.command?.let { command ->
            ReportLabel(stream = null)
            ToolOutputBlock(stringResource(Res.string.rows_tool_argument_line, command))
        }
        parsed.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (model.isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        parsed.streams.forEach { stream ->
            ReportLabel(stream)
            ToolOutputBlock(stream.text, isError = stream.kind == CommandStream.Kind.Stderr)
        }
    }
}

/** A section's heading: "Command" for the command ([stream] null), else the tail's own header. */
@Composable
private fun ReportLabel(stream: CommandStream?) {
    val text = when {
        stream == null -> stringResource(Res.string.rows_activity_command)
        stream.label != null -> stream.label.replace(" - ", " · ")
        else -> stringResource(Res.string.rows_tool_output)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = LettaDimens.Space.xs),
    )
}

@Composable
private fun NotificationActionRow(model: NotificationModel, expanded: Boolean, actions: NotificationActions) {
    val onOpen = actions.onOpen
    if (onOpen == null && !model.hasDetails()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
    ) {
        if (onOpen != null) {
            Text(
                text = stringResource(Res.string.rows_view_conversation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .defaultMinSize(minHeight = LettaDimens.Space.xxl)
                    .clickable(onClick = onOpen)
                    .padding(vertical = LettaDimens.Space.sm),
            )
        }
        if (model.hasDetails()) {
            val haptics = LocalHaptics.current
            DisclosureLink(label = detailsLabel(model, expanded), expanded = expanded, onToggle = {
                haptics.play(LettaHapticCue.SegmentTick)
                actions.onToggleDetails()
            })
        }
    }
}

@Composable
private fun detailsLabel(model: NotificationModel, expanded: Boolean): String {
    val resource = when {
        model.report != null -> if (expanded) Res.string.rows_hide_full_report else Res.string.rows_show_full_report
        model.isSuccessfulCompletion -> if (expanded) Res.string.rows_hide_details else Res.string.rows_show_details
        else -> if (expanded) Res.string.rows_hide_transcript else Res.string.rows_show_transcript
    }
    return stringResource(resource)
}

@Composable
private fun DisclosureLink(label: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = LettaDimens.Space.xxl)
            .clickable(onClick = onToggle)
            .padding(vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        DisclosureChevron(expanded = expanded, emphasis = ChevronEmphasis.Emphasized, compact = true, contentDescription = label)
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
        )
    }
}
