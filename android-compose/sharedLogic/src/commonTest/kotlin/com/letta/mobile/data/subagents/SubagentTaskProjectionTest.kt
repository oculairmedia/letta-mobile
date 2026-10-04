package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SubagentTaskProjectionTest {
    @Test
    fun partitionsRunningAndUnclearedTerminalTasks() {
        val projection = projectSubagentTasks(
            subagents = listOf(
                SubagentEntry("running", status = SubagentStatus.RUNNING),
                SubagentEntry("done", status = SubagentStatus.COMPLETED),
                SubagentEntry("cleared", status = SubagentStatus.FAILED),
            ),
            clearedKeys = setOf("cleared"),
            keyOf = { it.toolCallId },
        )

        assertEquals(listOf("running"), projection.running.map { it.toolCallId })
        assertEquals(listOf("done"), projection.finished.map { it.toolCallId })
    }

    @Test
    fun dropsHostReflectionRunsFromBothPartitions() {
        val projection = projectSubagentTasks(
            subagents = listOf(
                SubagentEntry("reflect", subagentType = "Reflection", status = SubagentStatus.RUNNING),
                SubagentEntry("integrate", subagentType = "Reflection integration", status = SubagentStatus.COMPLETED),
                SubagentEntry("work", subagentType = "General-purpose", status = SubagentStatus.RUNNING),
            ),
            clearedKeys = emptySet(),
            keyOf = { it.toolCallId },
        )

        assertEquals(listOf("work"), projection.running.map { it.toolCallId })
        assertEquals(emptyList(), projection.finished.map { it.toolCallId })
    }
}
