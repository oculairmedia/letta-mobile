package com.letta.mobile.testutil

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeAgentRepositoryTest {
    @Test
    fun `getContextWindow call log distinguishes null and empty conversation ids`() = runTest {
        val repository = FakeAgentRepository()

        repository.getContextWindow(agentId = "agent-1", conversationId = null)
        repository.getContextWindow(agentId = "agent-1", conversationId = "")

        assertEquals(
            listOf(
                "getContextWindow:agent-1:<null>",
                "getContextWindow:agent-1:",
            ),
            repository.calls,
        )
    }
}
