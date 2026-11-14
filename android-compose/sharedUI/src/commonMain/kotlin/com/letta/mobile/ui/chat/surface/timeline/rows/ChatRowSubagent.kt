package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import com.letta.mobile.data.chat.display.parseBackgroundCommandReport
import com.letta.mobile.data.chat.display.toInset
import com.letta.mobile.data.chat.display.TaskActivity
import com.letta.mobile.data.chat.display.activity
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.model.isCommand
import com.letta.mobile.data.subagents.SubagentIdentity
import com.letta.mobile.data.subagents.subagentIdentityOf
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_activity_command
import com.letta.mobile.sharedui.resources.rows_activity_subagent
import com.letta.mobile.sharedui.resources.rows_activity_task
import com.letta.mobile.sharedui.resources.rows_completed
import com.letta.mobile.sharedui.resources.rows_copy_transcript_path
import com.letta.mobile.sharedui.resources.rows_hide_details
import com.letta.mobile.sharedui.resources.rows_hide_full_report
import com.letta.mobile.sharedui.resources.rows_hide_transcript
import com.letta.mobile.sharedui.resources.rows_notification_headline
import com.letta.mobile.sharedui.resources.rows_show_details
import com.letta.mobile.sharedui.resources.rows_show_full_report
import com.letta.mobile.sharedui.resources.rows_show_transcript
import com.letta.mobile.sharedui.resources.rows_status_unknown
import com.letta.mobile.sharedui.resources.rows_task_id
import com.letta.mobile.sharedui.resources.rows_transcript
import com.letta.mobile.sharedui.resources.rows_view_conversation
import com.letta.mobile.ui.chat.subagents.ExternalAgentBadge
import com.letta.mobile.ui.components.SummaryLine
import com.letta.mobile.ui.components.line.DefaultDisclosureLabels
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.components.line.LineDisclosureLabels
import com.letta.mobile.ui.components.line.LineSpec
import com.letta.mobile.ui.components.line.LineTags
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowType
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaTonalPill
import com.letta.mobile.ui.theme.pill.LettaPillColors
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// letta-mobile-bglj6.1: Android's SubagentNotificationCard
// (ChatToolCallCards.kt), without haptics, opening the subagent through the host
// (ChatRowCallbacks.openSubagent) instead of LocalSubagentTodoSheetOpener.

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

    val isSuccessfulCompletion: Boolean = notification.isSuccessfulCompletion()

    /** What the line's glyph says: failed, still running, done, or a state the protocol does not name. */
    val tone: StatusTone = when (status) {
        in NOTIFICATION_FAILURE_STATUSES -> StatusTone.Failed
        in NOTIFICATION_RUNNING_STATUSES -> StatusTone.Working
        in NOTIFICATION_SUCCESS_STATUSES -> StatusTone.Done
        else -> StatusTone.Info
    }

    /** A report, or a transcript to point at: what the details disclosure has to show. */
    fun hasDetails(): Boolean {
        return report != null || notification.transcriptUri != null
    }
}

private val NOTIFICATION_FAILURE_STATUSES = setOf("failed", "error")
private val NOTIFICATION_SUCCESS_STATUSES = setOf("completed", "success")
private val NOTIFICATION_RUNNING_STATUSES = setOf("running", "pending", "queued", "started", "in_progress")

/**
 * The protocol names `completed` as its successful terminal state; older producers said `success`.
 * Only such a report compacts into one summary line: never a status that is not positively successful.
 */
internal fun UiSubagentNotification.isSuccessfulCompletion(): Boolean =
    status.trim().lowercase() in NOTIFICATION_SUCCESS_STATUSES

/**
 * A completed subagent contributes one compact row; its report, metadata and actions stay
 * behind a disclosure. In-flight, failed or unknown statuses keep their evidence visible.
 * Every state heads with the shared [SummaryLine] (completed, failed, in flight), on the rail where the
 * card is drawn: the header rail between runs, the step rail among a run's work steps, flush with the
 * step's label inside a tool step's details. A completed card adds no vertical padding of its own.
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
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.SUBAGENT_NOTIFICATION)
            .then(if (model.isSuccessfulCompletion) Modifier else Modifier.padding(vertical = LettaDimens.Space.sm)),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        if (model.isSuccessfulCompletion) {
            CompletedSummaryRow(model, detailsExpanded, actions.onToggleDetails)
            if (detailsExpanded) NotificationBody(model, detailsExpanded, actions)
        } else {
            NotificationHeader(model, detailsExpanded, actions.onToggleDetails)
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

/** The completed report's one line: the shared [SummaryLine], its chevron opening the details. */
@Composable
private fun CompletedSummaryRow(model: NotificationModel, expanded: Boolean, onToggle: () -> Unit) {
    val summary = model.notification.summary ?: stringResource(Res.string.rows_activity_subagent)
    SummaryLine(
        spec = LineSpec(
            title = summary,
            tone = StatusTone.Done,
            glyphLabel = stringResource(Res.string.rows_completed),
            tags = LineTags(row = ChatRowTestTags.COMPLETED_ACTIVITY_SUMMARY, glyph = ChatRowTestTags.COMPLETED_ACTIVITY_GLYPH),
        ),
        disclosure = LineDisclosure(expanded = expanded, labels = detailsLabels(model), onToggle = onToggle),
        trailing = { ExternalAgentBadge(model.identity) },
    )
}

/**
 * An in-flight, failed or unknown report's line ("Subagent failed"): the same [SummaryLine] as a
 * completed one, its glyph saying the state, its chevron opening the report or transcript when there
 * is one. Opening the conversation is the "View conversation" action under it.
 */
@Composable
private fun NotificationHeader(model: NotificationModel, expanded: Boolean, onToggle: () -> Unit) {
    val notification = model.notification
    val headline = notification.status.trim().ifBlank { stringResource(Res.string.rows_status_unknown) }
    SummaryLine(
        spec = LineSpec(
            title = stringResource(Res.string.rows_notification_headline, stringResource(notification.activityLabel()), headline),
            tone = model.tone,
            tags = LineTags(row = ChatRowTestTags.NOTIFICATION_HEADER, glyph = ChatRowTestTags.NOTIFICATION_GLYPH),
        ),
        disclosure = LineDisclosure(expanded = expanded, labels = detailsLabels(model), onToggle = onToggle).takeIf { model.hasDetails() },
        trailing = { ExternalAgentBadge(model.identity) },
    )
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
    NotificationActionRow(actions)
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
                    style = ChatRowType.monoLabel,
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
 * math and column-aligned output ran together into one paragraph, so command reports take a tool
 * step's inset (monospace, line-preserving, wrapped). A subagent's report
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
 * A shell's report as a tool step's own inset ([ToolInsetView]): the command, then each output tail,
 * stderr in the muted error ink whatever the exit code, so a traceback never reads as output. The
 * completion detail ("Exit code: 1") reads over it.
 */
@Composable
private fun CommandReport(model: NotificationModel) {
    val parsed = remember(model.report) { parseBackgroundCommandReport(model.report.orEmpty()) }
    val inset = remember(parsed) { parsed.toInset() }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.COMMAND_REPORT),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        parsed.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (model.isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        inset?.let { ToolInsetView(it) }
    }
}

/** "View conversation" for a subagent the host can open; the line above is the details' one disclosure. */
@Composable
private fun NotificationActionRow(actions: NotificationActions) {
    val onOpen = actions.onOpen ?: return
    Text(
        text = stringResource(Res.string.rows_view_conversation),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .defaultMinSize(minHeight = LettaDimens.Space.xxl)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(vertical = LettaDimens.Space.sm),
    )
}

/** What the details disclosure says: the full report, the details, or the transcript. */
private fun detailsLabels(model: NotificationModel): LineDisclosureLabels {
    val (show, hide) = when {
        model.report != null -> Res.string.rows_show_full_report to Res.string.rows_hide_full_report
        model.isSuccessfulCompletion -> Res.string.rows_show_details to Res.string.rows_hide_details
        else -> Res.string.rows_show_transcript to Res.string.rows_hide_transcript
    }
    return LineDisclosureLabels(show, hide, DefaultDisclosureLabels.expandedState, DefaultDisclosureLabels.collapsedState)
}

@Composable
private fun MetaChip(text: String) {
    LettaTonalPill(text, LettaPillColors(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer))
}
