package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.context.limit.ContextLimitRpc
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-joigh: the host's `conversation.context_limit` relay. */
class ConversationContextLimitHandlersTest {
    @Test
    fun itRunsContextLimitOnTheConversationAndReportsTheScope() = runTest {
        val client = CommandClient("Current conversation max context set to 1,000,000 tokens.")
        val response = dispatch(client, buildJsonObject {
            put("agent_id", "agent-1")
            put("conversation_id", "conv-7")
            put("tokens", 1_000_000)
        })
        assertEquals(true, response.getValue("success").jsonPrimitive.boolean, "$response")
        val result = response.getValue("result").jsonObject
        assertEquals(1_000_000, result.getValue("context_window").jsonPrimitive.int)
        assertEquals("conversation", result.getValue("applied_to").jsonPrimitive.content)
        val sent = client.commands.single()
        assertEquals("context-limit", sent.commandId)
        assertEquals("1000000", sent.args)
        assertEquals("conv-7", sent.runtime?.conversationId)
    }

    @Test
    fun withoutAConversationItTargetsTheAgentsDefault() = runTest {
        val client = CommandClient("Agent max context set to 200,000 tokens.")
        val result = dispatch(client, buildJsonObject {
            put("agent_id", "agent-1")
            put("tokens", 200_000)
        }).getValue("result").jsonObject
        assertEquals("agent", result.getValue("applied_to").jsonPrimitive.content)
        assertEquals("default", client.commands.single().runtime?.conversationId)
    }

    @Test
    fun anAppServerWithoutTheCommandIsCapabilityUnavailable() = runTest {
        val client = CommandClient("Unknown command: context-limit", success = false)
        val error = dispatch(client, params).getValue("error").jsonPrimitive.content
        assertTrue(error.startsWith("capability_unavailable"), error)
    }

    @Test
    fun aRefusalCarriesLettaCodesText() = runTest {
        val client = CommandClient("Failed: Context window must be at least 30,000 tokens. Use --override to apply a smaller value.", success = false)
        val error = dispatch(client, params).getValue("error").jsonPrimitive.content
        assertTrue(error.startsWith("context_limit_failed: Context window must be at least 30,000"), error)
    }

    @Test
    fun badTokensAreRefusedBeforeAnythingIsSent() = runTest {
        val client = CommandClient("unused")
        val response = dispatch(client, buildJsonObject {
            put("agent_id", "agent-1")
            put("tokens", "lots")
        })
        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue("invalid_tokens" in response.getValue("error").jsonPrimitive.content)
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun withoutANativeClientTheMethodIsUnavailable() = runTest {
        val router = AdminRpcRouter().also { ConversationContextLimitHandlers.register(it, nativeClient = null) }
        val response = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("t-1", ContextLimitRpc.METHOD, params))).jsonObject
        assertTrue(response.getValue("error").jsonPrimitive.content.startsWith("capability_unavailable"))
    }

    @Test
    fun theLimitIsAConversationManagementWriteLikeModelUpdate() {
        assertEquals(IrohPeerCapabilities.CONVERSATION_MANAGE, IrohPeerCapabilities.forAdminMethod(ContextLimitRpc.METHOD))
        assertTrue(ContextLimitRpc.METHOD in AdminRpcRegistry.canonicalMethods)
    }

    private val params = buildJsonObject {
        put("agent_id", "agent-1")
        put("tokens", 64_000)
    }

    private suspend fun dispatch(client: AppServerClient, params: JsonObject): JsonObject {
        val router = AdminRpcRouter().also { ConversationContextLimitHandlers.register(it, client) }
        return Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("t-1", ContextLimitRpc.METHOD, params))).jsonObject
    }

    private class CommandClient(private val output: String, private val success: Boolean = true) : AppServerClient {
        override val events = MutableSharedFlow<AppServerReceivedFrame>()
        val commands = mutableListOf<AppServerCommand.ExecuteCommand>()

        override suspend fun executeCommand(command: AppServerCommand.ExecuteCommand): AppServerInboundFrame.ExecuteCommandResponse {
            commands += command
            return AppServerInboundFrame.ExecuteCommandResponse(requestId = command.requestId, success = success, output = output)
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unexpected()
        override suspend fun input(command: AppServerCommand.Input): Unit = unexpected()
        override suspend fun sync(command: AppServerCommand.Sync) = unexpected()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unexpected()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = unexpected()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unexpected()

        private fun unexpected(): Nothing = error("Unexpected App Server operation")
    }
}
