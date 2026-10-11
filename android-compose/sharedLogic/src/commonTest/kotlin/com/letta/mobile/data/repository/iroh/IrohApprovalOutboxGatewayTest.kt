package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.chat.approval.ApprovalOutboxCoordinator
import com.letta.mobile.data.chat.approval.InMemoryApprovalOutboxStore
import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.data.runtime.ApprovalRelay
import com.letta.mobile.data.runtime.IrohApprovalControls
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * letta-mobile-bzvro.14 (F14) acceptance, over a fake Iroh channel: approve while the transport is
 * down, reconnect, and exactly one `approval.submit` reaches the host; a stale answer the host
 * refuses is dropped and its card removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohApprovalOutboxGatewayTest {
    private class FlakyTransport : NoOpChannelTransport() {
        val frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 8)
        val link = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Connected("server", "session", "device"))
        val submits = mutableListOf<String?>()
        var reject: String? = null
        override val events: SharedFlow<ServerFrame> = frames
        override val state: StateFlow<ChannelTransportState> = link
        override val hostCapabilities: Set<String> = setOf(ApprovalRelay.CAPABILITY)

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            if (link.value !is ChannelTransportState.Connected) error("Iroh connection is down")
            submits += body
            val error = reject
            return AppServerInboundFrame.AdminRpcResponse("r", success = error == null, result = Json.parseToJsonElement("{}"), error = error)
        }

        fun drop() {
            link.value = ChannelTransportState.Connecting(reconnecting = true)
        }

        fun restore() {
            link.value = ChannelTransportState.Connected("server", "session-2", "device")
        }
    }

    private val parked = PendingApprovalDetails(approvalId = "perm-call-1", toolCallId = "call-1", toolName = "Bash")

    private fun TestScope.gateway(transport: FlakyTransport) = IrohAdminRpcChatGateway(
        transport,
        approvalControls = IrohApprovalControls(
            backgroundScope,
            transport,
            PermissionModeSettings(MapSettingsStore()).modes,
            outboxStore = InMemoryApprovalOutboxStore(),
        ),
    )

    private suspend fun FlakyTransport.park(vararg details: PendingApprovalDetails) =
        frames.emit(ServerFrame.PendingApprovalsUpdated(agentId = "agent-1", conversationId = "conv-1", approvals = ApprovalRelay.encodeApprovals(details.toList())))

    @Test
    fun anApprovalMadeOfflineIsSentExactlyOnceAfterTheReconnect() = runTest(UnconfinedTestDispatcher()) {
        val transport = FlakyTransport()
        val gateway = gateway(transport)
        transport.park(parked)
        transport.drop()

        gateway.submitApproval("agent-1", "conv-1", "perm-call-1", "call-1", approve = true, reason = null)
        gateway.submitApproval("agent-1", "conv-1", "perm-call-1", "call-1", approve = true, reason = null)
        assertEquals(emptyList(), transport.submits, "nothing leaves while the link is down")
        assertEquals(setOf("perm-call-1"), gateway.queuedApprovalRequestIds.value, "the card says it is queued")

        transport.restore()
        advanceTimeBy(ApprovalOutboxCoordinator.DEFAULT_SETTLE.inWholeMilliseconds + 1)
        runCurrent()
        transport.drop()
        transport.restore()
        advanceTimeBy(ApprovalOutboxCoordinator.DEFAULT_SETTLE.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(1, transport.submits.size, "exactly one approval_response after two reconnects")
        assertEquals(emptySet(), gateway.queuedApprovalRequestIds.value)
        assertEquals(emptyMap(), gateway.pendingApprovalDetails.value, "the answered card goes")
        gateway.close()
    }

    @Test
    fun aRequestTheHostDroppedWhileOfflineIsNeverSent() = runTest(UnconfinedTestDispatcher()) {
        val transport = FlakyTransport()
        val gateway = gateway(transport)
        transport.park(parked)
        transport.drop()
        gateway.submitApproval("agent-1", "conv-1", "perm-call-1", "call-1", approve = false, reason = null)

        transport.restore()
        // The host's replay to the reconnected viewer: nothing is parked any more.
        transport.park()
        advanceTimeBy(ApprovalOutboxCoordinator.DEFAULT_SETTLE.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(emptyList(), transport.submits)
        assertEquals(emptySet(), gateway.queuedApprovalRequestIds.value)
        gateway.close()
    }

    @Test
    fun aStaleAnswerTheHostRefusesIsDroppedAndItsCardRemoved() = runTest(UnconfinedTestDispatcher()) {
        val transport = FlakyTransport()
        val gateway = gateway(transport)
        transport.park(parked)
        transport.reject = "approval_rejected: Approval request is no longer pending"

        assertFailsWith<IllegalStateException> {
            gateway.submitApproval("agent-1", "conv-1", "perm-call-1", "call-1", approve = true, reason = null)
        }

        assertEquals(1, transport.submits.size)
        assertEquals(emptyMap(), gateway.pendingApprovalDetails.value)
        assertEquals(emptySet(), gateway.queuedApprovalRequestIds.value, "a refused answer is not queued for a retry")
        gateway.close()
    }
}
