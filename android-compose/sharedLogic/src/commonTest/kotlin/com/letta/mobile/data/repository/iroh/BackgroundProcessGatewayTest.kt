package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.process.ProcessId

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.transport.api.AppServerClientChannel
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.ScriptedAppServerTransport
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.requestId
import com.letta.mobile.data.transport.appserver.stopBackgroundProcess
import com.letta.mobile.ui.chat.session.backgroundProcessHost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundProcessGatewayTest {
    @Test
    fun sharedGatewaySendsOneScopedStopAndRefusalReachesComposer() = runTest(UnconfinedTestDispatcher()) {
        val socket = ScriptedAppServerTransport { command ->
            listOf("""{"type":"admin_rpc_response","request_id":"${command.requestId}","success":false,"error":"host refused"}""")
        }
        val client = DefaultAppServerClient(socket, parentScope = backgroundScope)
        val gateway = IrohAdminRpcChatGateway(AppServerClientChannel(client, backgroundScope))
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val host = backgroundProcessHost(backgroundScope, gateway.deviceStatuses.map { it[runtime] },
            MutableStateFlow(runtime), gateway.stopBackgroundProcess)
        runCurrent()
        socket.push("""{"type":"toolset_status",
            "runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
            "device_status":{"background_processes":[{"id":"p"}]}}""")
        runCurrent()
        assertEquals(listOf("p"), host.value.backgroundProcesses.map { it.id })
        host.value.stopBackgroundProcess?.invoke("p")
        val call = socket.sent.single { it["method"]?.jsonPrimitive?.content == "monitor.stop" }
        val params = call.getValue("params").jsonObject
        runCurrent()
        assertEquals("p", params["process_id"]?.jsonPrimitive?.content)
        runCurrent()
        assertEquals("agent-1", params["agent_id"]?.jsonPrimitive?.content)
        runCurrent()
        assertEquals("conv-1", params["conversation_id"]?.jsonPrimitive?.content)
        runCurrent()
        assertEquals("host refused", host.value.backgroundProcessError)
        runCurrent()
        assertTrue(host.value.backgroundProcesses.isNotEmpty())
    }
    @Test
    fun switchingHostsRetiresCallbacksEvenWhenRuntimeIdsMatch() = runTest(UnconfinedTestDispatcher()) {
        fun socket() = ScriptedAppServerTransport { command ->
            listOf("""{"type":"admin_rpc_response","request_id":"${command.requestId}","success":true,"result":{"accepted":true}}""")
        }
        fun gateway(socket: ScriptedAppServerTransport) = IrohAdminRpcChatGateway(AppServerClientChannel(
            DefaultAppServerClient(socket, parentScope = backgroundScope), backgroundScope,
        ))
        val first = socket()
        val next = socket()
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val targets = MutableStateFlow<Pair<ChatGateway, AppServerRuntimeScope>?>(gateway(first) to runtime)
        val host = backgroundProcessHost(backgroundScope, targets) { targets.value }
        val status = """{"type":"update_device_status","event_seq":1,"emitted_at":"2026-11-15T00:00:00Z","idempotency_key":"status",
            "runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
            "device_status":{"background_processes":[{"id":"p"}]}}"""
        runCurrent()
        first.push(status)
        runCurrent()
        val oldStop = host.value.stopBackgroundProcess
        targets.value = gateway(next) to runtime
        oldStop?.invoke("p")
        runCurrent()
        assertTrue(host.value.backgroundProcesses.isEmpty())
        runCurrent()
        next.push(status)
        runCurrent()
        host.value.stopBackgroundProcess?.invoke("p")
        runCurrent()
        assertTrue(first.sent.none { it["method"]?.jsonPrimitive?.content == "monitor.stop" })
        runCurrent()
        assertEquals(1, next.sent.count { it["method"]?.jsonPrimitive?.content == "monitor.stop" })
    }

    @Test
    fun localNativeBindingUsesTheSameScopedProcessId() = runTest(UnconfinedTestDispatcher()) {
        val socket = ScriptedAppServerTransport { command ->
            listOf("""{"type":"monitor_stop_response","request_id":"${command.requestId}","success":true}""")
        }
        val client = DefaultAppServerClient(socket, parentScope = backgroundScope)
        client.stopBackgroundProcess(AppServerRuntimeScope("agent-1", "conv-1"), ProcessId("p"))
        val call = socket.sent.single()
        assertEquals("monitor_stop", call["type"]?.jsonPrimitive?.content)
        assertEquals("p", call["process_id"]?.jsonPrimitive?.content)
        val runtime = call.getValue("runtime").jsonObject
        assertEquals("agent-1", runtime["agent_id"]?.jsonPrimitive?.content)
        assertEquals("conv-1", runtime["conversation_id"]?.jsonPrimitive?.content)
    }

}
