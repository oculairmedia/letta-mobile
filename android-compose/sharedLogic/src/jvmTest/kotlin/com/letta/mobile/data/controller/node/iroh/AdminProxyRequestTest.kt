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
    fun messageListBuildsQueryWithQuestionMarkBeforeLimit() {
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        runTest {
            router.dispatch(
                requestId = "req-1",
                method = "message.list",
                params = buildJsonObject {
                    put("conversation_id", "conversation-1")
                    put("limit", "250")
                    put("order", "desc")
                },
            )
        }

        assertEquals("GET", recording.calls.single().method)
        assertEquals("http://admin.local/v1/conversations/conversation-1/messages?limit=250&order=desc", recording.calls.single().url)
    }

    @Test
    fun conversationListBuildsAgentScopedUrlWithQueryParams() {
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local/")

        runTest {
            router.dispatch(
                requestId = "req-1",
                method = "conversation.list",
                params = buildJsonObject {
                    put("agent_id", "agent-1")
                    put("limit", "25")
                    put("after", "cursor-2")
                    put("archive_status", "active")
                    put("summary_search", "needle")
                    put("order", "desc")
                    put("order_by", "created_at")
                },
            )
        }

        assertEquals(
            "http://admin.local/v1/agents/agent-1/conversations?limit=25&after=cursor-2&archive_status=active&summary_search=needle&order=desc&order_by=created_at",
            recording.calls.single().url,
        )
    }

    @Test
    fun conversationListBuildsRootUrlWithQueryParams() {
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        runTest {
            router.dispatch(
                requestId = "req-1",
                method = "conversation.list",
                params = buildJsonObject {
                    put("limit", "10")
                    put("order", "asc")
                },
            )
        }

        assertEquals("http://admin.local/v1/conversations?limit=10&order=asc", recording.calls.single().url)
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
    fun archivePatchesBaseConversationResourceWithArchivedTrue() = runTest {
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        router.dispatch(
            requestId = "req-1",
            method = "conversation.archive",
            params = buildJsonObject { put("conversation_id", "conv-1") },
        )

        val call = recording.calls.single()
        assertEquals("PATCH", call.method)
        // Letta has no /archive sub-resource; archive is a field toggled via
        // PATCH /v1/conversations/{id}. A phantom /archive path would 404.
        assertEquals("http://admin.local/v1/conversations/conv-1", call.url)
        assertEquals("""{"archived":true}""", call.body)
    }

    @Test
    fun restorePatchesBaseConversationResourceWithArchivedFalse() = runTest {
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        router.dispatch(
            requestId = "req-1",
            method = "conversation.restore",
            params = buildJsonObject { put("conversation_id", "conv-1") },
        )

        val call = recording.calls.single()
        assertEquals("PATCH", call.method)
        assertEquals("http://admin.local/v1/conversations/conv-1", call.url)
        assertEquals("""{"archived":false}""", call.body)
    }

    @Test
    fun queryParamValuesArePercentEncoded() {
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        runTest {
            router.dispatch(
                requestId = "req-1",
                method = "conversation.list",
                params = buildJsonObject {
                    put("summary_search", "space & hash #")
                },
            )
        }

        assertEquals("http://admin.local/v1/conversations?summary_search=space%20%26%20hash%20%23", recording.calls.single().url)
    }

    @Test
    fun non2xxUpstreamResponseDispatchesAsFailure() = runTest {
        val recording = installRecordingTransport(AdminProxyTransportResponse(404, """{"error":"missing"}"""))
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-404",
                method = "conversation.get",
                params = buildJsonObject { put("conversation_id", "missing") },
            ),
        ).jsonObject

        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.getValue("error").jsonPrimitive.content.contains("404"))
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
        val recording = installRecordingTransport(AdminProxyTransportResponse(500, upstreamBody))
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, "http://admin.local")

        val responseText = router.dispatch(
            requestId = "req-500",
            method = "conversation.get",
            params = buildJsonObject { put("conversation_id", "broken") },
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
