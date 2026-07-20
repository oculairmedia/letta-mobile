package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.UiSubagentNotification

internal fun extractSubagentNotification(raw: String): UiSubagentNotification? {
    val block = raw.taskNotificationBlock() ?: return null
    return UiSubagentNotification(
        toolCallId = block.firstXmlTag("tool_call_id", "toolCallId"),
        status = block.notificationStatus(),
        summary = block.xmlTag("summary"),
        result = block.xmlTag("result"),
        usage = block.xmlTag("usage"),
        transcriptUri = block.transcriptUri(),
        taskId = block.firstXmlTag("task_id", "taskId"),
        subagentAgentId = block.firstXmlTag("agent_id", "agentId"),
    )
}

private fun String.firstXmlTag(primary: String, alternate: String): String? =
    xmlTag(primary) ?: xmlTag(alternate)

private fun String.notificationStatus(): String =
    firstXmlTag("status", "state") ?: "completed"

private fun String.transcriptUri(): String? =
    xmlTag("transcript")
        ?: lineAfter("Full transcript at")
        ?: lineAfter("Full transcript:")
