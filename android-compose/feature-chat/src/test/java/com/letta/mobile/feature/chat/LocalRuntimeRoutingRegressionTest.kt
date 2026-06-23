package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LocalAgentRuntimeMetadata
import com.letta.mobile.feature.chat.coordination.LocalRuntimeRouting
import com.letta.mobile.feature.chat.screen.resolveLocalRuntimeRouting
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalRuntimeRoutingRegressionTest {
    @Test
    fun `local-agent cloud model routes remote instead of embedded runtime`() {
        val agent = Agent(
            id = AgentId("local-agent-1"),
            name = "Local shell, cloud model",
            model = "gpt-5.5",
        )

        assertEquals(LocalRuntimeRouting.Remote, resolveLocalRuntimeRouting(agent, localRuntimeBackendAvailable = true))
    }

    @Test
    fun `local-agent lmstudio cloud model routes remote instead of embedded runtime`() {
        val agent = Agent(
            id = AgentId("local-agent-1"),
            name = "Local shell, DeepSeek model",
            model = "lmstudio/deepseek-v4-flash",
        )

        assertEquals(LocalRuntimeRouting.Remote, resolveLocalRuntimeRouting(agent, localRuntimeBackendAvailable = true))
    }

    @Test
    fun `local-agent Gemma model routes embedded runtime`() {
        val agent = Agent(
            id = AgentId("local-agent-1"),
            name = "Gemma",
            model = "google/gemma-3n-E2B-it-litert-lm",
        )

        assertEquals(LocalRuntimeRouting.LocalBound, resolveLocalRuntimeRouting(agent, localRuntimeBackendAvailable = true))
    }

    @Test
    fun `local runtime metadata routes embedded runtime when model is local`() {
        val agent = Agent(
            id = AgentId("agent-1"),
            name = "Gemma metadata",
            model = "google/gemma-3n-E2B-it-litert-lm",
            metadata = mapOf(LocalAgentRuntimeMetadata.RuntimeKey to JsonPrimitive(LocalAgentRuntimeMetadata.LocalLettaCodeRuntime)),
        )

        assertEquals(LocalRuntimeRouting.LocalBound, resolveLocalRuntimeRouting(agent, localRuntimeBackendAvailable = true))
    }

    @Test
    fun `missing agent with local runtime available blocks instead of guessing by stale conversation id`() {
        assertEquals(LocalRuntimeRouting.Blocked(), resolveLocalRuntimeRouting(agent = null, localRuntimeBackendAvailable = true))
    }
}
