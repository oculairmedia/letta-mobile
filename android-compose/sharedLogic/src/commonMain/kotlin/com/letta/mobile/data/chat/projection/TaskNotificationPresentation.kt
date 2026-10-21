package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.model.isCommand

/** What produced a task notification; each client names it in its own copy. */
enum class TaskActivity { Command, Subagent, Task }

/** A task notification alone is not evidence of a subagent invocation. */
fun UiSubagentNotification.activity(): TaskActivity = when {
    isCommand() -> TaskActivity.Command
    !subagentAgentId.isNullOrBlank() -> TaskActivity.Subagent
    else -> TaskActivity.Task
}
