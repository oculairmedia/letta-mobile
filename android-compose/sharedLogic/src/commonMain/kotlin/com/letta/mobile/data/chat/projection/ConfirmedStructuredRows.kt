package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType

/**
 * A confirmed event that renders as its own structured row rather than a message: a compaction
 * divider (letta-mobile-kr39h) or a subagent's report carried in a user/assistant message.
 */
internal fun confirmedStructuredRow(ev: TimelineEvent.Confirmed): UiMessage? = when (ev.messageType) {
    TimelineMessageType.COMPACTION -> compactionUiMessage(ev)
    TimelineMessageType.USER, TimelineMessageType.ASSISTANT ->
        extractSubagentNotification(ev.content)?.let { subagentNotificationUiMessage(ev, it) }
    else -> null
}

private fun subagentNotificationUiMessage(ev: TimelineEvent.Confirmed, notification: UiSubagentNotification): UiMessage =
    UiMessage(
        id = ev.serverId,
        role = "assistant",
        content = "",
        timestamp = ev.date.toString(),
        runId = ev.runId,
        agentId = ev.agentId,
        stepId = ev.stepId,
        clientMessageId = ev.otid.takeIf { it.isNotBlank() },
        subagentNotification = notification,
    )

/** letta-mobile-kr39h: a compaction summary row; a blank summary still marks where it happened. */
private fun compactionUiMessage(ev: TimelineEvent.Confirmed): UiMessage = UiMessage(
    id = ev.serverId,
    role = "system",
    content = ev.content,
    timestamp = ev.date.toString(),
    agentId = ev.agentId,
    clientMessageId = ev.otid.takeIf { it.isNotBlank() },
    isCompaction = true,
)
