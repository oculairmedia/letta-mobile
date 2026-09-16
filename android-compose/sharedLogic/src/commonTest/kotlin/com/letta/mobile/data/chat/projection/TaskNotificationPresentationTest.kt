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
        assertEquals("Command", notification("exec_73").activityLabel())
        assertEquals("Task", notification("task_11").activityLabel())
        assertEquals("Task", notification(null).activityLabel())
        assertEquals("Subagent", notification("task_11", "agent-worker").activityLabel())
    }
}
