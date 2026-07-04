package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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
 * letta-mobile-fe51r (P2b pointer diet): round-trip coverage for the
 * `tool_return.get` admin_rpc method and for the message.list projection as
 * dispatched through the real router + handler stack.
 */
class ToolReturnGetAdminRpcTest {
    private val bigBody = "x".repeat(MessageListWireProjection.TOOL_RETURN_PROJECTION_THRESHOLD_BYTES * 2)

    private fun fullMessageJson(id: String = "msg-1") =
        """{"id":"$id","message_type":"tool_return_message","tool_call_id":"call-1","status":"success","tool_return":"$bigBody"}"""

    @Test
    fun toolReturnGetReturnsFullUnprojectedBody() = runTest {
        val recording = installRecordingTransport(AdminProxyTransportResponse(200, fullMessageJson()))
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-trg",
                method = "tool_return.get",
                params = buildJsonObject {
                    put("conversation_id", "conv-1")
                    put("message_id", "msg-1")
                },
            ),
        ).jsonObject

        assertEquals("GET", recording.calls.single().method)
        assertEquals("http://admin.local/v1/conversations/conv-1/messages/msg-1", recording.calls.single().url)
        assertTrue(response.getValue("success").jsonPrimitive.boolean)
        val result = response.getValue("result").jsonObject
        // Full body — no projection markers on the on-demand fetch path.
        assertEquals(bigBody, result.getValue("tool_return").jsonPrimitive.content)
        assertNull(result["tool_return_truncated"])
        assertNull(result["tool_return_pointer"])
    }

    @Test
    fun toolReturnGetMissingParamsDispatchesAsFailureEnvelope() = runTest {
        // letta-mobile-8vplf regression guard: parameter errors must encode
        // success=false (not a `{_error}` object inside success=true).
        installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        val response = Json.parseToJsonElement(
            router.dispatch(
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
        installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        val response = Json.parseToJsonElement(
            router.dispatch(requestId = "req-ml", method = "message.list", params = null),
        ).jsonObject

        assertFalse(response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.getValue("error").jsonPrimitive.content.contains("conversation_id"))
    }

    @Test
    fun messageListDispatchProjectsHeavyToolReturnBodies() = runTest {
        val page = """[${fullMessageJson()},{"id":"msg-user","message_type":"user_message","content":"hi"}]"""
        installRecordingTransport(AdminProxyTransportResponse(200, page))
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        val response = Json.parseToJsonElement(
            router.dispatch(
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
        // Untouched sibling in the same page.
        assertEquals("hi", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
    }

    private fun installRecordingTransport(
        response: AdminProxyTransportResponse = AdminProxyTransportResponse(200, """{"ok":true}"""),
    ): RecordingTransport {
        val recording = RecordingTransport(response)
        AdminProxyClient.defaultTransportFactory = { recording }
        return recording
    }

    private class RecordingTransport(
        private val response: AdminProxyTransportResponse,
    ) : AdminProxyTransport {
        val calls: MutableList<Call> = mutableListOf()

        override fun execute(method: String, url: String, body: String?): AdminProxyTransportResponse {
            calls += Call(method, url, body)
            return response
        }

        data class Call(val method: String, val url: String, val body: String?)
    }
}
