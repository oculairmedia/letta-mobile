package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DesktopBackgroundTasksPanelTest {
    @Test
    fun `entryKey uses toolCallId if present`() {
        val entry = SubagentEntry(
            toolCallId = "call_123",
            taskId = "task_456",
            description = "Some description",
            subagentType = "Researcher",
            status = SubagentStatus.RUNNING,
            startedAt = "2023-01-01T00:00:00Z"
        )
        assertEquals("call_123", entry.entryKey())
    }

    @Test
    fun `entryKey uses taskId if toolCallId is missing`() {
        val entry = SubagentEntry(
            toolCallId = "",
            taskId = "task_456",
            description = "Some description",
            subagentType = "Researcher",
            status = SubagentStatus.RUNNING,
            startedAt = "2023-01-01T00:00:00Z"
        )
        assertEquals("task_456", entry.entryKey())
    }

    @Test
    fun `entryKey uses composite key if both ids are missing`() {
        val entry1 = SubagentEntry(
            toolCallId = "",
            taskId = "",
            description = "Some description",
            subagentType = "Researcher",
            status = SubagentStatus.RUNNING,
            startedAt = "2023-01-01T00:00:00Z"
        )
        val entry2 = SubagentEntry(
            toolCallId = "",
            taskId = "",
            description = "Some description",
            subagentType = "Researcher",
            status = SubagentStatus.RUNNING,
            startedAt = "2023-01-01T00:00:01Z"
        )
        assertEquals("2023-01-01T00:00:00Z_Researcher_Some description", entry1.entryKey())
        assertEquals("2023-01-01T00:00:01Z_Researcher_Some description", entry2.entryKey())
        assertNotEquals(entry1.entryKey(), entry2.entryKey())
    }
}
