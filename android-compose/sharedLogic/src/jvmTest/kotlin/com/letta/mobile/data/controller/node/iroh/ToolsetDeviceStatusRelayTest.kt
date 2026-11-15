@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.api.ToolsetStatusStore
import com.letta.mobile.data.toolsets.ToolsetRuntime
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ToolsetDeviceStatusRelayTest {
    @Test
    fun forwardsAndReplaysOnlyToAuthorizedViewersAndClearsOnDisconnect() = runTest {
        val frames = MutableSharedFlow<AppServerReceivedFrame>()
        val connected = MutableStateFlow(true)
        val client = Proxy.newProxyInstance(AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java)) { _, method, _ ->
            when (method.name) {
                "getEvents" -> frames
                "isConnected" -> connected
                else -> error("Unexpected call: ${method.name}")
            }
        } as AppServerClient
        val registry = ConnectionRegistry()
        bindToolsetDeviceStatuses(backgroundScope, client, registry)
        val delivered = mutableListOf<String>()
        val allowed = viewer("allowed", delivered)
        registry.register("conv-1", registry.claim(allowed))
        val forbidden = mutableListOf<String>()
        registry.register("conv-1", registry.claim(viewer("wrong-agent", forbidden, agent = "other")))
        registry.register("conv-1", registry.claim(viewer("wrong-conversation", forbidden, conversation = "other")))
        registry.register("other", registry.claim(viewer("other-view", forbidden)))
        val status = AppServerInboundFrame.UpdateDeviceStatus(
            runtime = AppServerRuntimeScope("agent-1", "conv-1"), eventSeq = 7,
            emittedAt = "2026-11-15T00:00:00Z", idempotencyKey = "status-7",
            deviceStatus = buildJsonObject { put("current_toolset_preference", "auto") },
        )
        frames.emit(AppServerReceivedFrame(AppServerChannel.Stream, status, JsonObject(emptyMap())))
        runCurrent()
        assertEquals(1, delivered.size)
        assertTrue(forbidden.isEmpty())
        val wire = Json.parseToJsonElement(delivered.single()).jsonObject
        assertEquals(JsonPrimitive("toolset_status"), wire["type"])
        assertTrue("event_seq" !in wire, "native and relayed turns have separate sequence spaces")
        val store = ToolsetStatusStore()
        store.observe(AppServerProtocol.decodeFrame(delivered.single(), AppServerChannel.Stream))
        assertEquals("auto", store.states.value[ToolsetRuntime("agent-1", "conv-1")]?.preference)
        val replayed = mutableListOf<String>()
        registry.register("conv-1", registry.claim(viewer("late-view", replayed)))
        assertEquals(delivered, replayed)
        connected.value = false
        runCurrent()
        frames.emit(AppServerReceivedFrame(AppServerChannel.Stream, status, JsonObject(emptyMap())))
        runCurrent()
        val afterDisconnect = mutableListOf<String>()
        registry.register("conv-1", registry.claim(viewer("after-disconnect", afterDisconnect)))
        assertTrue(afterDisconnect.isEmpty())
    }

    private fun viewer(id: String, frames: MutableList<String>, agent: String = "agent-1", conversation: String = "conv-1") =
        object : ViewerHandle {
            override val connectionId = id
            override suspend fun writeFrame(frame: String): Boolean { frames += frame; return true }
            override fun adminContext() = AdminRpcRequestContext(
                authenticated = true, authorizedAgentIds = setOf(agent), authorizedConversationIds = setOf(conversation),
                grantedCapabilities = setOf(IrohPeerCapabilities.CHAT_READ),
            )
        }
}
