@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.toolsets.ToolsetRuntime
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import com.letta.mobile.ui.chat.session.backgroundProcessHost
import com.letta.mobile.data.transport.api.AppServerClientChannel
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ToolsetChannelTest {
    @Test
    fun browserChannelRoutesNonCronAdminRpcToSessionClient() = runTest {
        val calls = mutableListOf<AppServerCommand.AdminRpc>()
        val response = AppServerInboundFrame.AdminRpcResponse(
            requestId = "response", success = true,
        )
        val client = Proxy.newProxyInstance(
            AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getEvents" -> MutableSharedFlow<AppServerReceivedFrame>()
                "isConnected" -> MutableStateFlow(true)
                "adminRpc" -> {
                    calls += args[0] as AppServerCommand.AdminRpc
                    response
                }
                else -> error("Unexpected call: ${method.name}")
            }
        } as AppServerClient
        val channel = AppServerClientChannel(client, backgroundScope)
        assertEquals(response, channel.adminRpc("model.list", "/v1/models", null))
        assertEquals("model.list", calls.single().method)
    }

    @Test
    fun browserChannelIngestsHostNotificationsBeforeThePickerOpens() = runTest {
        val events = MutableSharedFlow<AppServerReceivedFrame>()
        val connected = MutableStateFlow(true)
        val client = Proxy.newProxyInstance(AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java)) { _, method, _ ->
            when (method.name) {
                "getEvents" -> events
                "isConnected" -> connected
                else -> error("Unexpected call: ${method.name}")
            }
        } as AppServerClient
        val channel = AppServerClientChannel(client, backgroundScope)
        events.emit(status())
        runCurrent()
        assertEquals("auto", channel.toolsetStates.value[ToolsetRuntime("agent-1", "conv-1")]?.preference)
        val shelf = backgroundProcessHost(backgroundScope, IrohAdminRpcChatGateway(channel), target)
        runCurrent()
        assertEquals(listOf("process-1"), shelf.value.backgroundProcesses.map { it.id }, "late shelf gets cached process data")
        connected.value = false
        runCurrent()
        assertTrue(channel.toolsetStates.value.isEmpty())
        assertTrue(shelf.value.backgroundProcesses.isEmpty(), "disconnect clears an already visible shelf")
        assertEquals(0, events.subscriptionCount.value, "the old session must release its eager collector")
        events.emit(status())
        runCurrent()
        assertTrue(channel.toolsetStates.value.isEmpty())
    }

    @Test
    fun androidAndDesktopIrohChannelIngestsAndDropsTheSameStatusOnDisconnect() = runTest {
        val events = MutableSharedFlow<AppServerReceivedFrame>()
        val transport = IrohChannelTransport(
            scope = backgroundScope,
            activeConfigProvider = { IrohConnectConfig("iroh://ticket", "", "device", "test") },
            testDialer = { config -> IrohConnectionHandle(config, "ticket", "session", observerStreamFrames = events, close = {}) },
        )
        try {
            transport.connect("iroh://ticket", "", "device", "test")
            runCurrent()
            events.emit(status())
            runCurrent()
            assertEquals("auto", transport.toolsetStates.value[ToolsetRuntime("agent-1", "conv-1")]?.preference)
            val shelf = backgroundProcessHost(backgroundScope, IrohAdminRpcChatGateway(transport), target)
            runCurrent()
            assertEquals(listOf("process-1"), shelf.value.backgroundProcesses.map { it.id })
            transport.disconnect()
            runCurrent()
            assertTrue(transport.toolsetStates.value.isEmpty())
            assertTrue(shelf.value.backgroundProcesses.isEmpty())
        } finally {
            transport.close()
        }
    }

    private val target = TimelineChatTarget("agent-1", "Ada", "conv-1")

    private fun status(): AppServerReceivedFrame {
        val wire = Json.parseToJsonElement("""{"type":"toolset_status",
            "runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
            "device_status":{"background_processes":[{"process_id":"process-1","kind":"monitor"}],
            "current_toolset_preference":"auto","available_toolsets":[{"id":"auto"},{"id":"codex"}]}}""").jsonObject
        return AppServerProtocol.decodeFrame(wire.toString(), AppServerChannel.Stream)
    }
}
