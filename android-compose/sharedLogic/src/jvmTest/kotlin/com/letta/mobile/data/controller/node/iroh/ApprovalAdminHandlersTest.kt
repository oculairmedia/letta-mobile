package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
    fun approvalSubmitRoutesLiveRuntimeApprovalThroughController() = runTest {
        val recording = installRecordingTransport()
        val controller = RecordingController()
        val router = AdminRpcRouter()
        ApprovalAdminHandlers.register(router, "http://admin.local", controller)

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-approval",
                method = "approval.submit",
                params = approvalParams(),
            ),
        ).jsonObject

        assertTrue(response.getValue("success").jsonPrimitive.boolean)
        assertEquals(0, recording.calls.size)
        assertEquals("agent-1", controller.submittedAgentId)
        assertEquals("approval-1", controller.submittedApprovalRequestId)
        assertEquals(true, controller.submittedApprove)
    }

    @Test
    fun approvalSubmitResolvesPendingApprovalThroughShimDecisionEndpoint() = runTest {
        val recording = installRecordingTransport { method, url, _ ->
            when {
                method == "GET" && url.contains("/shim/v1/approvals/pending") -> AdminProxyTransportResponse(
                    200,
                    """{"pending":[{"run_id":"run-1","agent_id":"agent-1","tool_call_id":"tool-a","status":"pending"}]}""",
                )
                method == "POST" && url.endsWith("/shim/v1/approvals/run-1/decision") -> AdminProxyTransportResponse(
                    200,
                    """{"status":"approved"}""",
                )
                else -> AdminProxyTransportResponse(404, "{}")
            }
        }
        val router = AdminRpcRouter()
        ApprovalAdminHandlers.register(router, "http://admin.local")

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-approval",
                method = "approval.submit",
                params = approvalParams(),
            ),
        ).jsonObject

        assertTrue(response.getValue("success").jsonPrimitive.boolean)
        assertEquals(2, recording.calls.size)
        assertEquals("GET", recording.calls[0].method)
        assertEquals("http://admin.local/shim/v1/approvals/pending?agent_id=agent-1", recording.calls[0].url)
        assertEquals("POST", recording.calls[1].method)
        assertEquals("http://admin.local/shim/v1/approvals/run-1/decision", recording.calls[1].url)
        assertTrue(recording.calls[1].body.orEmpty().contains("\"decision\":\"approve\""))
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

    private fun approvalParams() = buildJsonObject {
        put("agent_id", "agent-1")
        put("conversation_id", "conv-1")
        put(
            "payload",
            buildJsonObject {
                put("streaming", false)
                put(
                    "messages",
                    Json.parseToJsonElement(
                        """[{"type":"approval","approval_request_id":"approval-1","approve":true,"approvals":[{"tool_call_id":"tool-a","approve":true}]}]""",
                    ),
                )
            },
        )
    }

    private fun installRecordingTransport(
        responder: (method: String, url: String, body: String?) -> AdminProxyTransportResponse = { _, _, _ ->
            AdminProxyTransportResponse(200, """{"ok":true}""")
        },
    ): RecordingTransport {
        val recording = RecordingTransport(responder)
        AdminProxyClient.defaultTransportFactory = { recording }
        return recording
    }

    private class RecordingTransport(
        private val responder: (method: String, url: String, body: String?) -> AdminProxyTransportResponse,
    ) : AdminProxyTransport {
        val calls: MutableList<Call> = mutableListOf()
        override fun execute(method: String, url: String, body: String?): AdminProxyTransportResponse {
            calls += Call(method, url, body)
            return responder(method, url, body)
        }
        data class Call(val method: String, val url: String, val body: String?)
    }

    private class RecordingController : AppServerController {
        override val state: Flow<AppServerControllerState> = flowOf(AppServerControllerState.Connected)
        var submittedAgentId: String? = null
        var submittedApprovalRequestId: String? = null
        var submittedApprove: Boolean? = null

        override suspend fun startRuntime(
            agentId: AgentId,
            conversationId: ConversationId,
            cwd: String?,
            mode: AppServerPermissionMode?,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): CanonicalRuntime = error("not used")

        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = emptyFlow()

        override suspend fun sync(
            runtime: AppServerRuntimeScope,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): AppServerInboundFrame.SyncResponse = error("not used")

        override suspend fun abort(
            runtime: AppServerRuntimeScope,
            runId: String?,
        ): AppServerInboundFrame.AbortMessageResponse = error("not used")

        override suspend fun submitApproval(
            agentId: AgentId,
            conversationId: ConversationId?,
            approvalRequestId: String,
            approve: Boolean,
            reason: String?,
        ) {
            submittedAgentId = agentId.value
            submittedApprovalRequestId = approvalRequestId
            submittedApprove = approve
        }
    }
}
