package com.letta.mobile.feature.chat.send

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeRoutingTest {
    @Test
    fun `uses local runtime when session has local runtime backend`() {
        assertTrue(
            LocalRuntimeRouting.shouldUseLocalRuntime(
                sessionHasLocalRuntimeBackend = true,
                agentId = "agent-remote",
                conversationId = "conv-remote",
            )
        )
    }

    @Test
    fun `uses local runtime for local-bound agent even when global session is remote`() {
        assertTrue(
            LocalRuntimeRouting.shouldUseLocalRuntime(
                sessionHasLocalRuntimeBackend = false,
                agentId = "local-agent-123",
                conversationId = "conv-remote",
            )
        )
    }

    @Test
    fun `uses local runtime for local conversation even when global session is remote`() {
        assertTrue(
            LocalRuntimeRouting.shouldUseLocalRuntime(
                sessionHasLocalRuntimeBackend = false,
                agentId = "agent-remote",
                conversationId = "local-conv-local-agent-123",
            )
        )
    }

    @Test
    fun `uses remote runtime for remote agent and remote conversation`() {
        assertFalse(
            LocalRuntimeRouting.shouldUseLocalRuntime(
                sessionHasLocalRuntimeBackend = false,
                agentId = "agent-remote",
                conversationId = "conv-remote",
            )
        )
    }
}
