package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultConversationHistoryTest {
    private class StrictClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        val requests = mutableListOf<AppServerCommand.ConversationMessagesList>()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
        override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList):
            AppServerInboundFrame.ConversationMessagesListResponse {
            requests += command
            val valid = command.conversationId == "default" && command.query?.get("agent_id")?.jsonPrimitive?.content == "agent-child"
            val rows = if (valid) JsonArray(listOf(buildJsonObject {
                put("id", "child-message")
                put("message_type", "tool_return_message")
                put("tool_return", "child output")
            })) else JsonArray(emptyList())
            return AppServerInboundFrame.ConversationMessagesListResponse(command.requestId, valid, rows)
        }
    }

    private suspend fun read(
        client: StrictClient,
        context: AdminRpcRequestContext = AdminRpcRequestContext.Authenticated,
        method: String = "message.list",
    ): JsonObject {
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, tiers = NativeReadTiers(nativeClient = client))
        return Json.parseToJsonElement(router.dispatch(AdminRpcInvocation(
            requestId = "history",
            method = method,
            params = buildJsonObject {
                put("conversation_id", "conv-default-agent-child")
                put("message_id", "child-message")
                put("before", "newer-message")
            },
            context = context,
        ))).jsonObject
    }

    @Test
    fun aliasHistoryUsesNativeDefaultWithExplicitChildOwner() = runTest {
        val client = StrictClient()
        val response = read(client)
        assertTrue(response.getValue("success").jsonPrimitive.boolean, "Strict native backend must return child history: $response")
        assertEquals("child-message", (response.getValue("result") as JsonArray).single().jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("default", client.requests.single().conversationId)
        assertEquals("agent-child", client.requests.single().query?.get("agent_id")?.jsonPrimitive?.content)
        assertEquals("newer-message", client.requests.single().query?.get("before")?.jsonPrimitive?.content)
    }

    @Test
    fun aliasFullOutputAndMessageLookupUseResolvedTarget() = runTest {
        for (method in listOf("message.get", "tool_return.get")) {
            val response = read(StrictClient(), method = method)
            assertTrue(response.getValue("success").jsonPrimitive.boolean, response.toString())
            assertEquals("child output", response.getValue("result").jsonObject["tool_return"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun authorizedAgentCanReadAlias() = runTest {
        assertTrue(read(StrictClient(), AdminRpcRequestContext(true, authorizedAgentIds = setOf("agent-child")))
            .getValue("success").jsonPrimitive.boolean)
    }

    @Test
    fun otherAgentCannotReadAlias() = runTest {
        val client = StrictClient()
        val response = read(client, AdminRpcRequestContext(true, authorizedAgentIds = setOf("agent-other")))
        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.toString().contains("forbidden"))
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun aliasConversationGrantDoesNotOverrideAgentScope() = runTest {
        val client = StrictClient()
        val response = read(client, AdminRpcRequestContext(
            true,
            authorizedConversationIds = setOf("conv-default-agent-child"),
            authorizedAgentIds = setOf("agent-other"),
        ))
        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.toString().contains("forbidden"))
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun conversationScopeCannotUseAnotherDefaultsAlias() = runTest {
        val client = StrictClient()
        val response = read(client, AdminRpcRequestContext(true, authorizedConversationIds = setOf("conv-default-agent-other", "default")))
        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.toString().contains("forbidden"))
        assertTrue(client.requests.isEmpty())
    }
}
