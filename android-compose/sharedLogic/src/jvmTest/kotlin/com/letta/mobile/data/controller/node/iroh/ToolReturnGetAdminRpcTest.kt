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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-fe51r (P2b pointer diet) + Phase 2 native projection:
 * `tool_return.get` / `message.list` through the real router with App Server v2.
 */
class ToolReturnGetAdminRpcTest {
    private val bigBody = "x".repeat(MessageListWireProjection.TOOL_RETURN_PROJECTION_THRESHOLD_BYTES * 2)

    private fun fullMessage(id: String = "msg-1") = buildJsonObject {
        put("id", id)
        put("message_type", "tool_return_message")
        put("tool_call_id", "call-1")
        put("status", "success")
        put("tool_return", bigBody)
    }

    private class FakeMessagesClient(
        private val messages: JsonArray,
    ) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
        override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList) =
            AppServerInboundFrame.ConversationMessagesListResponse(command.requestId, true, messages)
    }

    private fun router(messages: JsonArray): AdminRpcRouter {
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(
            router,
            tiers = NativeReadTiers(nativeClient = FakeMessagesClient(messages)),
        )
        return router
    }

    @Test
    fun toolReturnGetReturnsFullUnprojectedBody() = runTest {
        NativeAdmin.resetCircuitForTest()
        val response = Json.parseToJsonElement(
            router(buildJsonArray { add(fullMessage()) }).dispatch(
                requestId = "req-trg",
                method = "tool_return.get",
                params = buildJsonObject {
                    put("conversation_id", "conv-1")
                    put("message_id", "msg-1")
                },
            ),
        ).jsonObject

        assertTrue(response.getValue("success").jsonPrimitive.boolean)
        val result = response.getValue("result").jsonObject
        assertEquals(bigBody, result.getValue("tool_return").jsonPrimitive.content)
        assertNull(result["tool_return_truncated"])
        assertNull(result["tool_return_pointer"])
    }

    @Test
    fun toolReturnGetMissingParamsDispatchesAsFailureEnvelope() = runTest {
        NativeAdmin.resetCircuitForTest()
        val response = Json.parseToJsonElement(
            router(JsonArray(emptyList())).dispatch(
                requestId = "req-missing",
                method = "tool_return.get",
                params = buildJsonObject { put("conversation_id", "conv-1") },
            ),
        ).jsonObject

        assertFalse(response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.getValue("error").jsonPrimitive.content.contains("message_id"))
        assertNull(response["result"])
    }

    @Test
    fun messageListMissingConversationIdDispatchesAsFailureEnvelope() = runTest {
        NativeAdmin.resetCircuitForTest()
        val response = Json.parseToJsonElement(
            router(JsonArray(emptyList())).dispatch(requestId = "req-ml", method = "message.list", params = null),
        ).jsonObject

        assertFalse(response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.getValue("error").jsonPrimitive.content.contains("conversation_id"))
    }

    @Test
    fun messageListDispatchProjectsHeavyToolReturnBodies() = runTest {
        NativeAdmin.resetCircuitForTest()
        val page = buildJsonArray {
            add(fullMessage())
            add(buildJsonObject {
                put("id", "msg-user")
                put("message_type", "user_message")
                put("content", "hi")
            })
        }
        val response = Json.parseToJsonElement(
            router(page).dispatch(
                requestId = "req-list",
                method = "message.list",
                params = buildJsonObject { put("conversation_id", "conv-1") },
            ),
        ).jsonObject

        assertTrue(response.getValue("success").jsonPrimitive.boolean)
        val messages = response.getValue("result").jsonArray
        val toolReturn = messages[0].jsonObject
        assertTrue(toolReturn.getValue("tool_return_truncated").jsonPrimitive.boolean)
        assertTrue(toolReturn.getValue("tool_return").jsonPrimitive.content.length < bigBody.length)
        val pointer = toolReturn.getValue("tool_return_pointer").jsonObject
        assertEquals("tool_return.get", pointer.getValue("method").jsonPrimitive.content)
        assertEquals("conv-1", pointer.getValue("conversation_id").jsonPrimitive.content)
        assertEquals("msg-1", pointer.getValue("message_id").jsonPrimitive.content)
        assertEquals("hi", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
    }
}
