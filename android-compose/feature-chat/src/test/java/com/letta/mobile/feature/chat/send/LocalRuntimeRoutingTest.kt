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
    fun `uses remote runtime for a remote letta-code-local conversation named local-conv-n`() {
        // Regression: the remote Meridian letta-code-local backend names its
        // conversations local-conv-<n> (local-conv-101), colliding with the
        // on-device local-conv-<localAgentId> naming. On a remote session these
        // must route REMOTE — routing them local hit a null on-device backend
        // ("missing_backend" / "local runtime not available").
        assertFalse(
            LocalRuntimeRouting.shouldUseLocalRuntime(
                sessionHasLocalRuntimeBackend = false,
                agentId = "agent-597b5756-2915-4560-ba6b-91005f085166",
                conversationId = "local-conv-101",
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
