package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Characterization test for letta-mobile-53k65.3:
 * Same-instance disconnect/reconnect generation isolation in [IrohChannelTransport].
 *
 * Sequence under test:
 * 1. Single transport instance connects to Handle #1 (session-1).
 * 2. Logical session state is established: viewed conversation is recorded via message.list adminRpc,
 *    and observer stream #1 is connected.
 * 3. Transport disconnects.
 * 4. Characterizes the immediate snapshot state on disconnect.
 * 5. Transport reconnects to Handle #2 (session-2).
 * 6. Delayed old-generation observer stream work cannot leak or mutate the new generation, while
 *    new observer stream #2 functions correctly.
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
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private data class AdminRpcRecord(val session: String, val method: String, val path: String)

    @Test
    fun characterizeDisconnectAndReconnectGenerationIsolation(): Unit = runBlocking {
        val stream1 = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val stream2 = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val adminRpcCalls = CopyOnWriteArrayList<AdminRpcRecord>()

        var dialCount = 0
        val dialGate1 = CompletableDeferred<Unit>()
        val dialGate2 = CompletableDeferred<Unit>()

        val transport = IrohChannelTransport(
            scope = clientScope,
            activeConfigProvider = { config },
            testDialer = { dialConfig ->
                dialCount += 1
                val dialNum = dialCount
                val session = "session-$dialNum"
                val observerStream = if (dialNum == 1) stream1 else stream2
                if (dialNum == 1) dialGate1.complete(Unit) else dialGate2.complete(Unit)

                IrohConnectionHandle(
                    config = dialConfig,
                    ticket = "ticket",
                    sessionId = session,
                    observerStreamFrames = observerStream,
                    adminRpcCall = { method, path, _ ->
                        adminRpcCalls.add(AdminRpcRecord(session, method, path))
                        AppServerInboundFrame.AdminRpcResponse(
                            requestId = "req-$dialNum",
                            success = true,
                            result = JsonPrimitive("ok-$session"),
                        )
                    },
                    close = {},
                )
            },
            serverTerminalWaitMs = 150L,
        )

        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }

        try {
            delay(150.milliseconds)

            // 1. Initial connect (session-1)
            transport.connect("iroh://ticket", "", "device", "test")
            withTimeout(5.seconds) {
                while (transport.state.value !is ChannelTransportState.Connected) delay(10.milliseconds)
            }
            withTimeout(5.seconds) {
                while (stream1.subscriptionCount.value < 1) delay(10.milliseconds)
            }

            // 2. Seed viewed conversation via real message.list adminRpc
            val viewedPath = "/v1/conversations/$CONV_A/messages?limit=50"
            val rpcResponse = transport.adminRpc("message.list", viewedPath, null)
            assertTrue(rpcResponse.success, "session-1 adminRpc must succeed")
            assertEquals(CONV_A, transport.viewedConversationIdSnapshot())
            assertEquals(viewedPath, transport.viewedMessageListPathSnapshot())

            // Deliver an assistant message on stream1 and verify ingestion
            stream1.emit(streamDelta(AGENT, CONV_A, 1, """{"message_type": "assistant_message", "id": "msg-1", "content": "from-session-1"}"""))
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.AssistantMessage && it.content == "from-session-1" }) delay(10.milliseconds)
            }

            // 3. Disconnect transport
            transport.disconnect()
            withTimeout(3.seconds) {
                while (transport.state.value !is ChannelTransportState.Disconnected) delay(10.milliseconds)
            }

            // 4. Characterize state immediately after disconnect:
            assertEquals(0, transport.activeTurnsCount(), "active turns empty after disconnect")
            assertEquals(0, transport.activeSendJobsCount(), "active send jobs empty after disconnect")
            assertFalse(transport.hasActiveChatTurn(CONV_A), "no active chat turn after disconnect")

            // On current main, disconnect preserves viewedMessageListPath (for reconnect resubscribe)
            val postDisconnectViewedPath = transport.viewedMessageListPathSnapshot()
            assertEquals(viewedPath, postDisconnectViewedPath, "characterization: viewed path preserved across disconnect for reconnect resubscribe")

            // Delayed frame delivered to old stream1 after disconnect must be dropped
            val framesCountAfterDisconnect = frames.size
            stream1.emit(streamDelta(AGENT, CONV_A, 2, """{"message_type": "assistant_message", "id": "msg-stale", "content": "stale-frame"}"""))
            delay(150.milliseconds)
            assertEquals(framesCountAfterDisconnect, frames.size, "stale stream1 frame must not be ingested after disconnect")

            // 5. Reconnect (session-2)
            transport.connect("iroh://ticket", "", "device", "test")
            withTimeout(5.seconds) {
                while (transport.state.value !is ChannelTransportState.Connected) delay(10.milliseconds)
            }
            withTimeout(5.seconds) {
                while (stream2.subscriptionCount.value < 1) delay(10.milliseconds)
            }

            // Fresh Ready triggered reSubscribeViewedConversationIfPresent on session-2
            withTimeout(3.seconds) {
                while (adminRpcCalls.none { it.session == "session-2" && it.method == "message.list" }) delay(10.milliseconds)
            }
            val resubscribeCall = adminRpcCalls.single { it.session == "session-2" && it.method == "message.list" }
            assertEquals(viewedPath, resubscribeCall.path, "session-2 auto-resubscribed viewed path")

            // 6. Deliver frame to new stream2 and verify ingestion
            stream2.emit(streamDelta(AGENT, CONV_A, 3, """{"message_type": "assistant_message", "id": "msg-2", "content": "from-session-2"}"""))
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.AssistantMessage && it.content == "from-session-2" }) delay(10.milliseconds)
            }

            // Verify late frame on old stream1 is still ignored
            val framesCountAfterNew = frames.size
            stream1.emit(streamDelta(AGENT, CONV_A, 4, """{"message_type": "assistant_message", "id": "msg-stale-2", "content": "stale-2"}"""))
            delay(150.milliseconds)
            assertEquals(framesCountAfterNew, frames.size, "late stream1 frame must never leak into session-2")
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV_A = "conv-a"

        fun streamDelta(agentId: String, conversationId: String, seq: Long, delta: String): AppServerReceivedFrame {
            val body = """
                {
                  "type": "stream_delta",
                  "runtime": {"agent_id": "$agentId", "conversation_id": "$conversationId"},
                  "event_seq": $seq,
                  "emitted_at": "2026-08-23T00:00:00Z",
                  "idempotency_key": "disc-$conversationId-$seq",
                  "delta": $delta
                }
            """.trimIndent()
            return AppServerProtocol.decodeFrame(body, AppServerChannel.Stream)
        }
    }
}
