package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.iroh.ControllerSubagentRegistrySource
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-fxoew.2: against a host that advertises the registry push, the
 * host is the one writer of subagent state. The client correlator stops
 * emitting, and the host's `subagents_updated` reaches the transport's events.
 */
class IrohSubagentHostPushGatingTest {
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observerStream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun transport(capabilities: Set<String>) = IrohChannelTransport(
        scope = clientScope,
        activeConfigProvider = { IrohConnectConfig("iroh://ticket", "", "device", "test") },
        testDialer = { config ->
            IrohConnectionHandle(
                config = config,
                ticket = "ticket",
                sessionId = "session",
                serverCapabilities = capabilities,
                observerStreamFrames = observerStream,
                close = {},
            )
        },
    )

    private fun agentDispatch(): AppServerReceivedFrame {
        val delta = buildJsonObject {
            put("message_type", "tool_call_message")
            put("run_id", "run-parent")
            put(
                "tool_call",
                buildJsonObject {
                    put("name", "Agent")
                    put("tool_call_id", "tc-1")
                    put("arguments", """{"description":"scout","subagent_type":"General-purpose"}""")
                },
            )
        }
        val frame = AppServerInboundFrame.StreamDelta(
            runtime = AppServerRuntimeScope(agentId = "agent-parent", conversationId = "conv-parent"),
            eventSeq = 1,
            emittedAt = "2026-10-03T00:00:01Z",
            idempotencyKey = "evt-1",
            delta = delta,
        )
        val raw = buildJsonObject {
            put("type", "stream_delta")
            put("idempotency_key", frame.idempotencyKey)
            put("delta", delta)
        }
        return AppServerReceivedFrame(channel = AppServerChannel.Stream, frame = frame, raw = raw)
    }

    private fun hostPush(): AppServerReceivedFrame {
        val raw: JsonObject = buildJsonObject {
            put("v", 1)
            put("type", "subagents_updated")
            put("id", "push-1")
            put("ts", "2026-10-03T00:00:02Z")
            put("reason", "registry")
            put(
                "subagents_active",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("toolCallId", "tc-1")
                            put("status", "completed")
                            put("parentAgentId", "agent-parent")
                            put("parentConversationId", "conv-parent")
                        },
                    )
                },
            )
        }
        return AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = AppServerInboundFrame.Unknown(type = "subagents_updated", raw = raw),
            raw = raw,
        )
    }

    private suspend fun connected(transport: IrohChannelTransport) {
        transport.connect("iroh://ticket", "", "device", "test")
        withTimeout(3.seconds) {
            while (transport.state.value !is ChannelTransportState.Connected) delay(10.milliseconds)
        }
        delay(100.milliseconds)
    }

    /**
     * Emits one parent Agent dispatch, then a host push as an ordering fence:
     * frames are ingested in order, so once the push is seen any correlator
     * frame for the dispatch has already been emitted. Returns how many
     * correlator (non-host) SubagentsUpdated frames there were.
     */
    private suspend fun correlatorFramesAfterDispatch(capabilities: Set<String>): Int {
        val transport = transport(capabilities)
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            connected(transport)
            assertTrue(observerStream.tryEmit(agentDispatch()))
            assertTrue(observerStream.tryEmit(hostPush()))
            withTimeout(3.seconds) { while (frames.none { it.isHostPush() }) delay(10.milliseconds) }
            return frames.count { it is ServerFrame.SubagentsUpdated && !it.isHostPush() }
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    private fun ServerFrame.isHostPush(): Boolean = this is ServerFrame.SubagentsUpdated && reason == "registry"

    @Test
    fun `correlator stays silent when the host pushes the registry`() = runBlocking {
        assertEquals(0, correlatorFramesAfterDispatch(setOf(IrohObserverIngestor.SUBAGENT_PUSH_CAPABILITY)))
    }

    @Test
    fun `correlator still emits against a host without the push`() = runBlocking {
        assertEquals(1, correlatorFramesAfterDispatch(setOf(IrohChannelTransport.SUBAGENT_RPC_CAPABILITY)))
    }

    @Test
    fun `host subagents_updated push is republished with its terminal entry`() = runBlocking {
        val transport = transport(setOf(IrohObserverIngestor.SUBAGENT_PUSH_CAPABILITY))
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            connected(transport)
            assertTrue(observerStream.tryEmit(hostPush()))
            withTimeout(3.seconds) { while (frames.none { it is ServerFrame.SubagentsUpdated }) delay(10.milliseconds) }
            val pushed = frames.filterIsInstance<ServerFrame.SubagentsUpdated>().single()
            assertEquals(listOf("completed"), pushed.subagentsActive.map { it.status })
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    @Test
    fun `client push capability matches the host constant`() {
        assertEquals(ControllerSubagentRegistrySource.PUSH_CAPABILITY, IrohObserverIngestor.SUBAGENT_PUSH_CAPABILITY)
    }
}
