package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiSubagentNotification
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskNotificationPresentationTest {
    @Test
    fun commandsAndUnknownTasksAreNotInventedSubagents() {
        fun notification(task: String?, agent: String? = null) = UiSubagentNotification(
            status = "completed", summary = null, result = null, usage = null,
            transcriptUri = null, taskId = task, subagentAgentId = agent,
        )
        assertEquals(TaskActivity.Command, notification("exec_73").activity())
        // letta-mobile-2a7yv: a background Bash shell is a command too.
        assertEquals(TaskActivity.Command, notification("bash_54").activity())
        assertEquals(TaskActivity.Task, notification("task_11").activity())
        assertEquals(TaskActivity.Task, notification(null).activity())
        assertEquals(TaskActivity.Subagent, notification("task_11", "agent-worker").activity())
    }
}
