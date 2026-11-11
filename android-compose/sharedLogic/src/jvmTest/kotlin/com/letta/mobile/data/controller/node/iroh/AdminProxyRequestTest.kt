package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminProxyRequestTest {
    @Test
    fun runtimeOwnedMessageListDoesNotDialProxyWithoutNativeClient() = runTest {
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router)

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
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router)

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
    fun archiveListBuildsGetPath() {
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ProjectAdminHandlers.register(router, "http://admin.local")

        runTest {
            router.dispatch(
                requestId = "req-1",
                method = "project.list",
                params = null,
            )
        }

        val call = recording.calls.single()
        assertEquals("GET", call.method)
        assertEquals("http://admin.local/api/projects", call.url)
    }

    @Test
    fun deprecatedGoalCommandIsCapabilityUnavailable() = runTest {
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        GoalAdminHandlers.register(router)

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-1",
                method = "goal.command",
                params = buildJsonObject {
                    put("agent_id", "agent-1")
                    put("command", "pause")
                },
            ),
        ).jsonObject

        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.getValue("error").jsonPrimitive.content.contains("capability_unavailable"))
        assertTrue(recording.calls.isEmpty())
    }

    @Test
    fun runtimeOwnedArchiveDoesNotDialProxyWithoutNativeClient() = runTest {
        val recording = installRecordingTransport()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router)

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
        val failure = dispatchUpstreamFailure(AdminProxyTransportResponse(404, """{"error":"missing"}"""))

        // Status only: vibesync's body is its own text and never crosses the wire.
        failure.assertStatusOnly("HTTP 404", "missing")
        assertEquals(1, failure.recording.calls.size)
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

        val failure = dispatchUpstreamFailure(AdminProxyTransportResponse(500, upstreamBody))

        failure.assertStatusOnly("HTTP 500", "first line", "quoted", "and newline")
    }

    @Test
    fun non2xxUpstreamBodyQuotingAHostPathNeverReachesTheClientOrTelemetry() = runTest {
        val hostPath = "/srv/vibesync/data/projects/registry.db"
        val upstreamBody = """{"error":"EACCES: permission denied, open '$hostPath'"}"""

        val failure = dispatchUpstreamFailure(AdminProxyTransportResponse(409, upstreamBody))

        failure.assertStatusOnly("HTTP 409", hostPath, "EACCES")
    }

    private class UpstreamFailure(
        val responseText: String,
        val events: List<Telemetry.Event>,
        val recording: RecordingTransport,
    ) {
        fun assertStatusOnly(expected: String, vararg upstreamFragments: String) {
            val response = Json.parseToJsonElement(responseText).jsonObject
            assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
            assertEquals(expected, response.getValue("error").jsonPrimitive.content)
            val telemetry = events.joinToString(separator = " | ") { it.toLine() } + events
            upstreamFragments.forEach { fragment ->
                assertFalse(fragment in responseText, "response leaked '$fragment'")
                assertFalse(fragment in telemetry, "telemetry leaked '$fragment'")
            }
        }
    }

    private suspend fun dispatchUpstreamFailure(upstream: AdminProxyTransportResponse): UpstreamFailure {
        val recording = installRecordingTransport(upstream)
        val recorder = TelemetryRecorder()
        return Telemetry.installRecorder(recorder).use {
            val router = AdminRpcRouter()
            ProjectAdminHandlers.register(router, "http://admin.local")
            val responseText = router.dispatch(requestId = "req-upstream", method = "project.list", params = null)
            UpstreamFailure(responseText, recorder.snapshot(), recording)
        }
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
