package com.letta.mobile.data.repository.appserver

import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Verifies [AppServerLocalAdminGateway.createAgent] and
 * [AppServerLocalAdminGateway.listLlmModels] against the App Server's
 * runtime-native `agent_create` / `list_models` commands — the SAME commands
 * the bundled `@letta-ai/letta-code` 0.29.12 local runtime actually serves
 * (verified by grepping the bundled `letta.js`: it has no `admin_rpc`
 * handling at all — its `app_server_info` capability advertisement never
 * sets an `admin_rpc` flag — but does dispatch `agent_create` and
 * `list_models` as first-class Listen V2 commands).
 */
class AppServerLocalAdminGatewayModelAndAgentTest {
    @Test
    fun `createAgent posts a body defaulted with context window and decodes the created agent`() = runTest {
        val client = FakeClient(
            agentCreateResponse = { command ->
                AppServerInboundFrame.AgentCreateResponse(
                    requestId = command.requestId,
                    success = true,
                    agent = buildJsonObject {
                        put("id", "agent-created-1")
                        put("name", "Ada")
                        put("model", "openai/gpt-4o")
                    },
                )
            },
        )
        val gateway = AppServerLocalAdminGateway(client) { "request-$it" }

        val agent = gateway.createAgent(AgentCreateParams(name = "Ada", model = "openai/gpt-4o"))

        assertEquals(AgentId("agent-created-1"), agent.id)
        assertEquals("Ada", agent.name)
        val sentBody = client.lastAgentCreate?.body
        assertEquals("Ada", sentBody?.get("name")?.jsonPrimitive?.content)
        assertEquals("openai/gpt-4o", sentBody?.get("model")?.jsonPrimitive?.content)
        // withDefaultContextWindow fills a context_window_limit for an
        // unrecognized handle so the bundled runtime never silently caps at 0.
        assertTrue(sentBody?.containsKey("context_window_limit") == true)
    }

    @Test
    fun `createAgent fails closed when the runtime rejects the mutation`() = runTest {
        val client = FakeClient(
            agentCreateResponse = { command ->
                AppServerInboundFrame.AgentCreateResponse(
                    requestId = command.requestId,
                    success = false,
                    error = "local runtime unavailable",
                )
            },
        )
        val gateway = AppServerLocalAdminGateway(client) { "request-$it" }

        val failure = assertFailsWith<IllegalStateException> {
            gateway.createAgent(AgentCreateParams(name = "Ada"))
        }

        assertEquals("local runtime unavailable", failure.message)
    }

    @Test
    fun `listLlmModels adapts list_models presentation entries into the model catalog`() = runTest {
        val client = FakeClient(
            listModelsResponse = { command ->
                AppServerInboundFrame.ListModelsResponse(
                    requestId = command.requestId,
                    success = true,
                    entries = JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", "litellm/gpt-4o")
                                put("handle", "litellm/gpt-4o")
                                put("label", "GPT-4o (litellm)")
                            },
                        ),
                    ),
                )
            },
        )
        val gateway = AppServerLocalAdminGateway(client) { "request-$it" }

        val models = gateway.listLlmModels()

        assertEquals(1, models.size)
        assertEquals("litellm/gpt-4o", models.single().handle)
        assertEquals("GPT-4o (litellm)", models.single().name)
    }

    @Test
    fun `listLlmModels degrades to an empty catalog instead of throwing on failure`() = runTest {
        val client = FakeClient(
            listModelsResponse = { command ->
                AppServerInboundFrame.ListModelsResponse(
                    requestId = command.requestId,
                    success = false,
                    error = "model catalog unavailable",
                )
            },
        )
        val gateway = AppServerLocalAdminGateway(client) { "request-$it" }

        assertEquals(emptyList(), gateway.listLlmModels())
    }

    private class FakeClient(
        private val agentCreateResponse: (AppServerCommand.AgentCreate) -> AppServerInboundFrame.AgentCreateResponse = {
            error("agentCreate not stubbed")
        },
        private val listModelsResponse: (AppServerCommand.ListModels) -> AppServerInboundFrame.ListModelsResponse = {
            error("listModels not stubbed")
        },
    ) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        var lastAgentCreate: AppServerCommand.AgentCreate? = null

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unsupported()
        override suspend fun input(command: AppServerCommand.Input): Unit = unsupported()
        override suspend fun sync(command: AppServerCommand.Sync) = unsupported()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unsupported()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = unsupported()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unsupported()

        override suspend fun agentCreate(command: AppServerCommand.AgentCreate): AppServerInboundFrame.AgentCreateResponse {
            lastAgentCreate = command
            return agentCreateResponse(command)
        }

        override suspend fun listModels(command: AppServerCommand.ListModels): AppServerInboundFrame.ListModelsResponse =
            listModelsResponse(command)

        private fun unsupported(): Nothing = error("Unexpected App Server operation")
    }
}
