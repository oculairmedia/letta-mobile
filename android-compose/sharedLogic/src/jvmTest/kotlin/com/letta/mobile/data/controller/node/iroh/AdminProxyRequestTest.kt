package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminProxyRequestTest {
    @Test
    fun runtimeOwnedMessageListDoesNotDialProxyWithoutNativeClient() = runTest {
        NativeAdmin.resetCircuitForTest()
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-1",
                method = "message.list",
                params = buildJsonObject {
                    put("conversation_id", "conversation-1")
                    put("limit", "250")
                    put("order", "desc")
                },
            ),
        ).jsonObject

        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.getValue("error").jsonPrimitive.content.contains("capability_unavailable"))
        assertTrue(recording.calls.isEmpty())
    }

    @Test
    fun runtimeOwnedConversationListDoesNotDialProxyWithoutNativeClient() = runTest {
        NativeAdmin.resetCircuitForTest()
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local/")

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-1",
                method = "conversation.list",
                params = buildJsonObject {
                    put("agent_id", "agent-1")
                    put("limit", "25")
                },
            ),
        ).jsonObject

        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.getValue("error").jsonPrimitive.content.contains("capability_unavailable"))
        assertTrue(recording.calls.isEmpty())
    }

    @Test
    fun goalCommandBuildsPostPath() {
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        GoalAdminHandlers.register(router, "http://admin.local")

        runTest {
            router.dispatch(
                requestId = "req-1",
                method = "goal.command",
                params = buildJsonObject {
                    put("agent_id", "agent-1")
                    put("command", "pause")
                },
            )
        }

        val call = recording.calls.single()
        assertEquals("POST", call.method)
        assertEquals("http://admin.local/v1/agents/agent-1/goal/command", call.url)
        assertEquals("""{"command":"pause"}""", call.body)
    }

    @Test
    fun runtimeOwnedArchiveDoesNotDialProxyWithoutNativeClient() = runTest {
        NativeAdmin.resetCircuitForTest()
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-1",
                method = "conversation.archive",
                params = buildJsonObject { put("conversation_id", "conv-1") },
            ),
        ).jsonObject

        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.getValue("error").jsonPrimitive.content.contains("capability_unavailable"))
        assertTrue(recording.calls.isEmpty())
    }

    @Test
    fun queryParamValuesArePercentEncoded() {
        val request = AdminPath.v1("conversations").builder()
            .query("summary_search", "space & hash #")
            .build()
        assertEquals(
            "http://admin.local/v1/conversations?summary_search=space%20%26%20hash%20%23",
            request.url("http://admin.local"),
        )
    }

    @Test
    fun non2xxUpstreamResponseDispatchesAsFailure() = runTest {
        val recording = installRecordingTransport(AdminProxyTransportResponse(404, """{"error":"missing"}"""))
        val router = AdminRpcRouter()
        ArchiveAdminHandlers.register(router, "http://admin.local")

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-404",
                method = "archive.list",
                params = null,
            ),
        ).jsonObject

        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.getValue("error").jsonPrimitive.content.contains("404"))
        assertEquals(1, recording.calls.size)
    }

    @Test
    fun non2xxUpstreamResponseWithMultilineBodyDispatchesValidJsonFailure() = runTest {
        val upstreamBody = """
            {
              "error": "first line",
              "detail": "quoted \"value\"
and newline"
            }
        """.trimIndent()
        installRecordingTransport(AdminProxyTransportResponse(500, upstreamBody))
        val router = AdminRpcRouter()
        ArchiveAdminHandlers.register(router, "http://admin.local")

        val responseText = router.dispatch(
            requestId = "req-500",
            method = "archive.list",
            params = null,
        )
        val response = Json.parseToJsonElement(responseText).jsonObject

        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        val error = response.getValue("error").jsonPrimitive.content
        assertTrue(error.contains("HTTP 500"))
        assertTrue(error.contains("quoted \\\"value\\\""))
        assertTrue(error.contains("and newline"))
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
