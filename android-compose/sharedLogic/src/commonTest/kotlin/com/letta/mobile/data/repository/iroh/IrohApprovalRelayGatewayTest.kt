package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.data.runtime.ApprovalRelay
import com.letta.mobile.data.runtime.IrohApprovalControls
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.runtime.PermissionSuggestion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-bzvro.40: desktop over `iroh://` through [IrohAdminRpcChatGateway]: the host's parked
 * approvals arrive as `pending_approvals` frames on the transport, and an always-allow goes to the
 * host as `selected_permission_suggestion_ids` on `approval.submit`, never to an older host.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohApprovalRelayGatewayTest {
    private class RelayTransport(var capabilities: Set<String>) : NoOpChannelTransport() {
        val frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 8)
        val bodies = mutableListOf<Pair<String, String?>>()
        override val events: SharedFlow<ServerFrame> = frames
        override val state: StateFlow<ChannelTransportState> =
            MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val hostCapabilities: Set<String> get() = capabilities

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            bodies += method to body
            return AppServerInboundFrame.AdminRpcResponse(requestId = "r", success = true, result = Json.parseToJsonElement("{}"))
        }
    }

    private val parked = PendingApprovalDetails(
        approvalId = "perm-call-1",
        toolCallId = "call-1",
        toolName = "Bash",
        suggestions = listOf(PermissionSuggestion("rule-1", "Allow npm test")),
    )

    @Test
    fun theHostsSnapshotsBecomeTheGatewaysPendingDetails() = runTest(UnconfinedTestDispatcher()) {
        val transport = RelayTransport(setOf(ApprovalRelay.CAPABILITY))
        val gateway = gateway(transport)

        transport.frames.emit(push(listOf(parked)))
        assertEquals(mapOf("call-1" to parked), gateway.pendingApprovalDetails.value)

        transport.frames.emit(push(emptyList()))
        assertEquals(emptyMap(), gateway.pendingApprovalDetails.value)
        gateway.close()
    }

    @Test
    fun alwaysAllowSendsTheRuleIdsAndRetiresTheCard() = runTest(UnconfinedTestDispatcher()) {
        val transport = RelayTransport(setOf(ApprovalRelay.CAPABILITY))
        val gateway = gateway(transport)
        transport.frames.emit(push(listOf(parked)))

        gateway.submitAlwaysAllow("agent-1", "conv-1", "perm-call-1", "call-1", listOf("rule-1"))

        val (method, body) = transport.bodies.single()
        assertEquals("approval.submit", method)
        val approval = Json.parseToJsonElement(body!!).jsonObject.getValue("payload").jsonObject
            .getValue("messages").jsonArray.single().jsonObject
        assertEquals("perm-call-1", approval.getValue("approval_request_id").jsonPrimitive.content)
        assertEquals(listOf("rule-1"), approval.getValue("selected_permission_suggestion_ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(emptyMap(), gateway.pendingApprovalDetails.value, "the card goes once the host took the answer")
        gateway.close()
    }

    @Test
    fun anOlderHostIsNeverSentARuleAndOffersNoChip() = runTest(UnconfinedTestDispatcher()) {
        val transport = RelayTransport(emptySet())
        val gateway = gateway(transport)

        assertFailsWith<UnsupportedOperationException> {
            gateway.submitAlwaysAllow("agent-1", "conv-1", "perm-call-1", "call-1", listOf("rule-1"))
        }
        assertTrue(transport.bodies.isEmpty())
        assertNull(gateway.permissionModes)
        gateway.close()
    }

    private fun kotlinx.coroutines.test.TestScope.gateway(transport: RelayTransport) = IrohAdminRpcChatGateway(
        transport,
        approvalControls = IrohApprovalControls(backgroundScope, transport, PermissionModeSettings(MapSettingsStore()).modes),
    )

    private fun push(details: List<PendingApprovalDetails>) = ServerFrame.PendingApprovalsUpdated(
        agentId = "agent-1",
        conversationId = "conv-1",
        approvals = ApprovalRelay.encodeApprovals(details),
    )
}
