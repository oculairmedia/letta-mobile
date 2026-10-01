package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.chat.projection.extractSubagentNotification
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_activity_command
import com.letta.mobile.sharedui.resources.rows_activity_subagent
import com.letta.mobile.sharedui.resources.rows_activity_task
import com.letta.mobile.sharedui.resources.rows_completed
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
import com.letta.mobile.sharedui.resources.rows_transcript
import com.letta.mobile.sharedui.resources.rows_view_conversation
import com.letta.mobile.ui.components.ChevronEmphasis
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.markdown.SharedMarkdownText
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
        MetaChip(dispatch.subagentType)
    }
}

@Composable
private fun DispatchPrompt(dispatch: UiSubagentDispatch) {
    var expanded by remember(dispatch.toolCallId, dispatch.prompt) { mutableStateOf(false) }
    val label = stringResource(if (expanded) Res.string.rows_hide_prompt else Res.string.rows_show_prompt)
    DisclosureLink(label = label, expanded = expanded) { expanded = !expanded }
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

private fun dispatchStatus(toolStatus: String): StringResource = when (toolStatus.lowercase()) {
    "success", "succeeded", "completed", "complete", "done" -> Res.string.rows_dispatch_done
    "error", "failed" -> Res.string.rows_dispatch_failed
    else -> Res.string.rows_dispatch_running
}

/** The return half: a subagent's `<task-notification>`. */
@Immutable
private data class NotificationModel(
    val notification: UiSubagentNotification,
    val target: ChatSubagentTarget?,
    val report: String?,
) {
    val status: String = notification.status.trim().lowercase()
    val isFailure: Boolean = status == "failed" || status == "error"

    // The protocol names `completed` as its successful terminal state; older producers said
    // `success`. Never compact a status that is not positively successful.
    val isSuccessfulCompletion: Boolean = status == "completed" || status == "success"
    val hasDetails: Boolean = report != null || notification.transcriptUri != null
}

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
    val isSubagent = notification.activityLabel() == Res.string.rows_activity_subagent
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

private fun UiSubagentNotification.activityLabel(): StringResource = when {
    taskId?.startsWith("exec_") == true -> Res.string.rows_activity_command
    !subagentAgentId.isNullOrBlank() -> Res.string.rows_activity_subagent
    else -> Res.string.rows_activity_task
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
    }
}

@Composable
private fun NotificationBody(model: NotificationModel, expanded: Boolean, actions: NotificationActions) {
    val notification = model.notification
    notification.summary?.let { summary ->
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        notification.usage?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
        notification.durationMs?.let { MetaChip(formatDuration(it)) }
        notification.taskId?.let { MetaChip(it) }
    }
    NotificationActionRow(model, expanded, actions)
    if (expanded && model.hasDetails) {
        model.report?.let { SharedMarkdownText(text = it) }
        notification.transcriptUri?.let { transcript ->
            Text(
                text = stringResource(Res.string.rows_transcript, transcript),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NotificationActionRow(model: NotificationModel, expanded: Boolean, actions: NotificationActions) {
    val onOpen = actions.onOpen
    if (onOpen == null && !model.hasDetails) return
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
        if (model.hasDetails) {
            DisclosureLink(label = detailsLabel(model, expanded), expanded = expanded, onToggle = actions.onToggleDetails)
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
