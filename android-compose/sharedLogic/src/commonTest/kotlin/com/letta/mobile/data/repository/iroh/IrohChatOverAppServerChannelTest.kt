package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.data.runtime.ApprovalRelay
import com.letta.mobile.data.runtime.IrohApprovalControls
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.data.runtime.PermissionModeRpc
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.runtime.RelayedPendingApprovals
import com.letta.mobile.data.transport.api.AppServerClientChannel
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.ScriptedAppServerTransport
import com.letta.mobile.data.transport.appserver.requestId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared Iroh chat gateway and its approval controls over an App Server session's channel, the
 * way the browser reaches an Iroh host: the host's pushes and `admin_rpc` ride the App Server client.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohChatOverAppServerChannelTest {
    private class Host(val transport: ScriptedAppServerTransport, val gateway: IrohAdminRpcChatGateway) {
        fun adminMethods(): List<String> = transport.sent
            .filter { it["type"]?.jsonPrimitive?.content == "admin_rpc" }
            .map { it.getValue("method").jsonPrimitive.content }

        fun adminParams(method: String): JsonObject = transport.sent
            .single { it["method"]?.jsonPrimitive?.content == method }
            .getValue("params").jsonObject
    }

    private fun TestScope.host(capabilities: Set<String>, result: (method: String) -> String = { "{}" }): Host {
        val transport = ScriptedAppServerTransport { command ->
            if (command["type"]?.jsonPrimitive?.content != "admin_rpc") return@ScriptedAppServerTransport emptyList()
            val method = command.getValue("method").jsonPrimitive.content
            listOf("""{"type":"admin_rpc_response","request_id":"${command.requestId}","success":true,"result":${result(method)}}""")
        }
        val client = DefaultAppServerClient(transport, parentScope = backgroundScope)
        val channel = AppServerClientChannel(client, backgroundScope, capabilities)
        val controls = IrohApprovalControls(backgroundScope, channel, PermissionModeSettings(MapSettingsStore()).modes)
        return Host(transport, IrohAdminRpcChatGateway(channel, approvalControls = controls))
    }

    @Test
    fun aHostParkedApprovalShowsAndItsAnswerReachesTheHost() = runTest(UnconfinedTestDispatcher()) {
        val host = host(setOf(ApprovalRelay.CAPABILITY))
        val parked = PendingApprovalDetails(approvalId = "approval-1", toolCallId = "call-1", toolName = "Bash")

        host.transport.push(ApprovalRelay.encodeFrame(RelayedPendingApprovals("agent-1", "conv-1", listOf(parked)), ts = "1").toString())
        assertEquals(setOf("call-1"), host.gateway.pendingApprovalDetails.first { it.isNotEmpty() }.keys)

        host.gateway.submitApproval("agent-1", "conv-1", "approval-1", "call-1", approve = true, reason = null)

        assertEquals("agent-1", host.adminParams("approval.submit").getValue("agent_id").jsonPrimitive.content)
        assertTrue(host.gateway.pendingApprovalDetails.value.isEmpty(), "an accepted answer takes its card down")
        assertTrue(host.gateway.queuedApprovalRequestIds.value.isEmpty())
    }

    @Test
    fun theModeChipNeedsTheHostCapabilityAndASwitchIsConfirmedByTheHost() = runTest(UnconfinedTestDispatcher()) {
        assertNull(host(emptySet()).gateway.permissionModes, "an older host keeps no mode chip")

        val host = host(setOf(PermissionModeRpc.CAPABILITY)) { method ->
            val confirmed = PermissionModeRpc.result(AppServerPermissionMode.AcceptEdits, confirmed = true)
            if (method == PermissionModeRpc.SET) confirmed.toString() else "{}"
        }
        assertNotNull(host.gateway.permissionModes)

        val confirmed = host.gateway.setPermissionMode(AppServerRuntimeScope("agent-1", "conv-1"), AppServerPermissionMode.AcceptEdits)

        assertTrue(confirmed)
        assertEquals(listOf(PermissionModeRpc.SET), host.adminMethods())
    }
}
