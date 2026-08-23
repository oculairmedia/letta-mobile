package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Characterizes same-instance disconnect/reconnect generation isolation in
 * [IrohChannelTransport] through its public state, RPC, and event surfaces.
 */
class IrohChannelTransportDisconnectGenerationTest {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config = IrohConnectConfig(
        baseShimUrl = "iroh://ticket",
        token = "",
        deviceId = "device",
        clientVersion = "test",
    )

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun characterizeDisconnectAndReconnectGenerationIsolation(): Unit = runBlocking {
        val harness = TransportHarness()
        try {
            harness.connect(observerIndex = 0)
            val viewedPath = harness.seedViewedConversation()
            harness.emitAndAwait(observerIndex = 0, sequence = 1, content = "from-session-1")

            harness.disconnectAndAssertPublicState()
            harness.emitAndAssertIgnored(observerIndex = 0, sequence = 2, content = "stale-frame")

            harness.connect(observerIndex = 1)
            harness.assertViewedConversationResubscribed(viewedPath)
            harness.emitAndAwait(observerIndex = 1, sequence = 3, content = "from-session-2")
            harness.emitAndAssertIgnored(observerIndex = 0, sequence = 4, content = "stale-2")
        } finally {
            harness.close()
        }
    }

    private inner class TransportHarness {
        private val observerStreams = List(2) {
            MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        }
        private val adminRpcCalls = CopyOnWriteArrayList<AdminRpcRecord>()
        private val frames = CopyOnWriteArrayList<ServerFrame>()
        private var dialCount = 0

        val transport = IrohChannelTransport(
            scope = clientScope,
            activeConfigProvider = { config },
            testDialer = ::dial,
            serverTerminalWaitMs = 150L,
        )
        private val collector = clientScope.async(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { frames.add(it) }
        }

        suspend fun connect(observerIndex: Int) {
            transport.connect("iroh://ticket", "", "device", "test")
            awaitCondition { transport.state.value is ChannelTransportState.Connected }
            awaitCondition { observerStreams[observerIndex].subscriptionCount.value == 1 }
        }

        suspend fun seedViewedConversation(): String {
            val viewedPath = "/v1/conversations/$CONVERSATION/messages?limit=50"
            assertTrue(transport.adminRpc("message.list", viewedPath, null).success)
            return viewedPath
        }

        suspend fun disconnectAndAssertPublicState() {
            transport.disconnect()
            awaitCondition { transport.state.value is ChannelTransportState.Disconnected }
            assertFalse(transport.hasActiveChatTurn(CONVERSATION))
            assertFalse(transport.hasAnyActiveChatTurn)
        }

        suspend fun assertViewedConversationResubscribed(expectedPath: String) {
            awaitCondition { adminRpcCalls.any { it.session == "session-2" && it.method == "message.list" } }
            val call = adminRpcCalls.single { it.session == "session-2" && it.method == "message.list" }
            assertEquals(expectedPath, call.path)
        }

        suspend fun emitAndAwait(observerIndex: Int, sequence: Long, content: String) {
            observerStreams[observerIndex].emit(streamDelta(sequence, content))
            awaitCondition { frames.hasAssistantContent(content) }
        }

        suspend fun emitAndAssertIgnored(observerIndex: Int, sequence: Long, content: String) {
            observerStreams[observerIndex].emit(streamDelta(sequence, content))
            delay(STALE_FRAME_SETTLE_TIME)
            assertFalse(frames.hasAssistantContent(content))
        }

        suspend fun close() {
            collector.cancelAndJoin()
            transport.disconnect()
        }

        private suspend fun dial(dialConfig: IrohConnectConfig): IrohConnectionHandle {
            val dialNumber = ++dialCount
            val session = "session-$dialNumber"
            return IrohConnectionHandle(
                config = dialConfig,
                ticket = "ticket",
                sessionId = session,
                observerStreamFrames = observerStreams[dialNumber - 1],
                adminRpcCall = { method, path, _ ->
                    adminRpcCalls.add(AdminRpcRecord(session, method, path))
                    AppServerInboundFrame.AdminRpcResponse(
                        requestId = "req-$dialNumber",
                        success = true,
                        result = JsonPrimitive("ok-$session"),
                    )
                },
                close = {},
            )
        }
    }

    private data class AdminRpcRecord(val session: String, val method: String, val path: String)

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-a"
        val STALE_FRAME_SETTLE_TIME = 150.milliseconds

        suspend fun awaitCondition(predicate: () -> Boolean) {
            withTimeout(5.seconds) {
                while (!predicate()) delay(10.milliseconds)
            }
        }

        fun List<ServerFrame>.hasAssistantContent(content: String): Boolean =
            any { it is ServerFrame.AssistantMessage && it.content == content }

        fun streamDelta(sequence: Long, content: String): AppServerReceivedFrame {
            val body = """
                {
                  "type": "stream_delta",
                  "runtime": {"agent_id": "$AGENT", "conversation_id": "$CONVERSATION"},
                  "event_seq": $sequence,
                  "emitted_at": "2026-08-23T00:00:00Z",
                  "idempotency_key": "disc-$CONVERSATION-$sequence",
                  "delta": {"message_type": "assistant_message", "id": "msg-$sequence", "content": "$content"}
                }
            """.trimIndent()
            return AppServerProtocol.decodeFrame(body, AppServerChannel.Stream)
        }
    }
}
