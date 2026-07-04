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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-qfa81 (P4 row 13): server-side `approval.submit` dispatch through
 * the real router + [ApprovalAdminHandlers], backed by a recording proxy.
 */
class ApprovalAdminHandlersTest {
    @Test
    fun approvalSubmitForwardsPayloadToAgentMessagesEndpoint() = runTest {
        val recording = installRecordingTransport(AdminProxyTransportResponse(200, """{"id":"msg-out"}"""))
        val router = AdminRpcRouter()
        ApprovalAdminHandlers.register(router, "http://admin.local")

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-approval",
                method = "approval.submit",
                params = buildJsonObject {
                    put("agent_id", "agent-1")
                    put(
                        "payload",
                        buildJsonObject {
                            put("streaming", false)
                            put(
                                "messages",
                                Json.parseToJsonElement("""[{"type":"approval","approval_request_id":"approval-1"}]"""),
                            )
                        },
                    )
                },
            ),
        ).jsonObject

        assertTrue(response.getValue("success").jsonPrimitive.boolean)
        val call = recording.calls.single()
        assertEquals("POST", call.method)
        assertEquals("http://admin.local/v1/agents/agent-1/messages", call.url)
        assertTrue(call.body.orEmpty().contains("\"approval_request_id\":\"approval-1\""))
    }

    @Test
    fun approvalSubmitMissingAgentIdDispatchesFailureEnvelope() = runTest {
        installRecordingTransport()
        val router = AdminRpcRouter()
        ApprovalAdminHandlers.register(router, "http://admin.local")

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-missing",
                method = "approval.submit",
                params = buildJsonObject {
                    put("payload", buildJsonObject { put("streaming", false) })
                },
            ),
        ).jsonObject

        assertFalse(response.getValue("success").jsonPrimitive.boolean)
        assertTrue(response.getValue("error").jsonPrimitive.content.contains("agent_id"))
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
