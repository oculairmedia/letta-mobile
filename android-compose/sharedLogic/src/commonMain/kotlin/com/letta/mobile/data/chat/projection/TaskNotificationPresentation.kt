package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiSubagentNotification

/** A task notification alone is not evidence of a subagent invocation. */
fun UiSubagentNotification.activityLabel(): String = when {
    taskId?.startsWith("exec_") == true -> "Command"
    !subagentAgentId.isNullOrBlank() -> "Subagent"
    else -> "Task"
}
