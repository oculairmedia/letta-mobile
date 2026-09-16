package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import com.letta.mobile.feature.chat.coordination.assistantRunGroups

class RunAutoCollapseTest {

    @Test
    fun `blank run ids stay ungrouped`() {
        val groups = assistantRunGroups(
            listOf(
                message(id = "assistant-blank", runId = ""),
                message(id = "assistant-null", runId = null),
                message(id = "user", role = "user", runId = null),
            ),
        )

        assertEquals(emptyList<String>(), groups.map { it.runId })
    }

    @Test
    fun `groups preserve first-appearance order so newest is deterministic`() {
        val groups = assistantRunGroups(
            listOf(
                message(id = "a", runId = "run-1"),
                message(id = "b", runId = "run-2"),
                message(id = "c", runId = "run-1"),
            ),
        )

        assertEquals(listOf("run-1", "run-2"), groups.map { it.runId })
        assertEquals(listOf("a", "c"), groups.single { it.runId == "run-1" }.messages.map { it.id })
    }

    private fun message(
        id: String,
        role: String = "assistant",
        runId: String? = "run-1",
        isPending: Boolean = false,
        isReasoning: Boolean = false,
    ) = UiMessage(
        id = id,
        role = role,
        content = id,
        timestamp = "2026-05-02T12:00:00Z",
        runId = runId,
        isPending = isPending,
        isReasoning = isReasoning,
    )
}
