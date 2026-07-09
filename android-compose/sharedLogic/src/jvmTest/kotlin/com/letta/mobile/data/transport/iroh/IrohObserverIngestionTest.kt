package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.data.runtime.AppServerTurnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-r3i1z (MOBILE observer ingestion).
 *
 * Proves a PASSIVE observer — connected, with NO local turn active — ingests the
 * fanned-out stream_delta frames the server already writes to its stream channel
 * and emits correctly-shaped [ServerFrame]s into _events/_frameEvents, identical
 * to the initiator path. Also proves the DUAL-INGEST guard: while a local turn is
 * active on a conversation, the observer collector does NOT double-emit that
 * conversation's frames (the engine owns them), yet it still ingests frames for a
 * DIFFERENT conversation. And that ingestion stops on close.
 */
class IrohObserverIngestionTest {
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /** A stream_delta wire frame exactly as the server fans it out to a viewer. */
    private fun streamDelta(
        agentId: String,
        conversationId: String,
        seq: Long,
        delta: String,
    ): AppServerReceivedFrame {
        val body = """
            {
              "type": "stream_delta",
              "runtime": {"agent_id": "$agentId", "conversation_id": "$conversationId"},
              "event_seq": $seq,
              "emitted_at": "2026-07-09T00:00:0${seq}Z",
              "idempotency_key": "obs-evt-$conversationId-$seq",
              "delta": $delta
            }
        """.trimIndent()
        return AppServerProtocol.decodeFrame(body, AppServerChannel.Stream)
    }

    private fun userEchoDelta(otid: String, text: String) =
        """{"message_type": "user_message", "id": "cm-user-$otid", "otid": "$otid", "content": "$text"}"""

    private fun assistantDelta(id: String, content: String) =
        """{"message_type": "assistant_message", "id": "$id", "content": "$content"}"""

    private fun stopReasonDelta(reason: String = "end_turn") =
        """{"message_type": "stop_reason", "stop_reason": "$reason"}"""

    /**
     * Build a connected transport whose passive-observer ingestion loop reads from
     * [stream] (a test-injected flow standing in for the live QUIC stream channel).
     * When [engine] is provided, the SAME handle exposes it so a local turn can run.
     */
    private suspend fun connectedTransport(
        stream: MutableSharedFlow<AppServerReceivedFrame>,
        engine: AppServerTurnEngine? = null,
    ): IrohChannelTransport {
        val transport = IrohChannelTransport(
            scope = clientScope,
            activeConfigProvider = { IrohConnectConfig("iroh://ticket", "", "device", "test") },
            testDialer = { config ->
                IrohConnectionHandle(
                    config = config,
                    ticket = "ticket",
                    sessionId = "session",
                    turnEngine = engine,
                    observerStreamFrames = stream,
                    close = {},
                )
            },
        )
        transport.connect("iroh://ticket", "", "device", "test")
        return transport
    }

    // ============ (a) OBSERVER INGESTION — no local turn ================
    @Test
    fun passiveObserverIngestsFannedOutSequenceIntoEvents() = runBlocking {
        val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val transport = connectedTransport(stream)
        val frames = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            // Let the observer collector subscribe to the stream before we emit.
            withTimeout(2_000) { while (transport.state.value !is com.letta.mobile.data.transport.ChannelTransportState.Connected) delay(10) }
            delay(100)

            // A full fanned-out turn the client did NOT initiate: user echo,
            // cumulative assistant deltas, then a terminal stop_reason.
            stream.emit(streamDelta("agent-1", "conv-obs", 1, userEchoDelta("otid-user", "hello observer")))
            stream.emit(streamDelta("agent-1", "conv-obs", 2, assistantDelta("cm-stream-x", "Hi")))
            stream.emit(streamDelta("agent-1", "conv-obs", 3, assistantDelta("cm-stream-x", "Hi there")))
            stream.emit(streamDelta("agent-1", "conv-obs", 4, stopReasonDelta("end_turn")))

            withTimeout(5_000) {
                while (frames.none { it is ServerFrame.TurnDone }) delay(20)
            }

            val user = frames.filterIsInstance<ServerFrame.UserMessage>()
            val assistant = frames.filterIsInstance<ServerFrame.AssistantMessage>()
            val terminal = frames.filterIsInstance<ServerFrame.TurnDone>()

            assertEquals(1, user.size, "exactly one user echo row; got ${frames.map { it::class.simpleName }}")
            assertEquals("hello observer", user.single().content)
            assertEquals("otid-user", user.single().otid)
            assertEquals("cm-user-otid-user", user.single().id)
            assertEquals("conv-obs", user.single().conversationId)

            assertTrue(assistant.isNotEmpty(), "assistant stream ingested")
            assertEquals("Hi there", assistant.last().content, "final cumulative assistant text")
            // Stable otid anchored on the cm-stream id — all fragments share it.
            assertEquals(assistant.first().otid, assistant.last().otid, "assistant fragments share stable otid")

            assertEquals(1, terminal.size, "exactly one terminal")
            assertEquals("completed", terminal.single().status)
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    // ============ (b) DUAL-INGEST GUARD ================================
    @Test
    fun observerSkipsActiveTurnConversationButIngestsOthers() = runBlocking {
        // A never-completing engine so the local turn stays active (activeTurn set),
        // exercising the dual-ingest guard window.
        val engine = AppServerTurnEngine(client = BlockingInputClient(conversationId = "conv-local"))
        val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val transport = connectedTransport(stream, engine = engine)
        val frames = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            withTimeout(2_000) { while (transport.state.value !is com.letta.mobile.data.transport.ChannelTransportState.Connected) delay(10) }
            delay(100)

            // Start a LOCAL turn on conv-local; activeTurn is set synchronously.
            assertTrue(transport.send("agent-1", "conv-local", "hi", "otid-local", null, false))
            withTimeout(2_000) { while (!engine.isBusy) delay(10) }

            // Frames for the ACTIVE-turn conversation arrive on the fanout stream:
            // the engine owns them — observer MUST NOT emit them.
            stream.emit(streamDelta("agent-1", "conv-local", 10, assistantDelta("cm-stream-local", "engine owned")))
            // Frames for a DIFFERENT conversation belong to no local turn: observer ingests.
            stream.emit(streamDelta("agent-1", "conv-other", 11, userEchoDelta("otid-other", "other convo")))
            stream.emit(streamDelta("agent-1", "conv-other", 12, assistantDelta("cm-stream-other", "other reply")))

            withTimeout(5_000) {
                while (frames.none { it is ServerFrame.AssistantMessage && it.content == "other reply" }) delay(20)
            }
            delay(200) // give any (erroneous) engine-owned emit a chance to appear

            // The observer emitted conv-other frames...
            assertTrue(
                frames.any { it is ServerFrame.UserMessage && it.conversationId == "conv-other" },
                "observer ingested the other conversation's user echo",
            )
            assertTrue(
                frames.any { it is ServerFrame.AssistantMessage && it.content == "other reply" },
                "observer ingested the other conversation's assistant reply",
            )
            // ...but did NOT double-emit the active-turn conversation's frame.
            assertTrue(
                frames.none { it is ServerFrame.AssistantMessage && it.content == "engine owned" },
                "observer must NOT emit frames the engine owns for the active turn's conversation; " +
                    "got ${frames.filterIsInstance<ServerFrame.AssistantMessage>().map { it.content }}",
            )
        } finally {
            collector.cancel()
            runCatching { transport.cancel("conv-local") }
            transport.disconnect()
        }
    }

    /** An AppServerClient whose input() blocks forever, keeping the engine turn active. */
    private class BlockingInputClient(private val conversationId: String) : com.letta.mobile.data.transport.appserver.AppServerClient {
        override val events = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
        override suspend fun runtimeStart(command: com.letta.mobile.data.transport.appserver.AppServerCommand.RuntimeStart) =
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId, success = true,
                runtime = com.letta.mobile.data.transport.appserver.AppServerRuntimeScope(
                    agentId = command.agentId ?: "agent-1", conversationId = command.conversationId ?: conversationId,
                ),
            )
        override suspend fun input(command: com.letta.mobile.data.transport.appserver.AppServerCommand.Input) { kotlinx.coroutines.awaitCancellation() }
        override suspend fun sync(command: com.letta.mobile.data.transport.appserver.AppServerCommand.Sync) = throw UnsupportedOperationException()
        override suspend fun abort(command: com.letta.mobile.data.transport.appserver.AppServerCommand.AbortMessage) =
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AbortMessageResponse(
                requestId = command.requestId ?: "req",
                runtime = command.runtime, aborted = true, success = true,
            )
        override suspend fun adminRpc(command: com.letta.mobile.data.transport.appserver.AppServerCommand.AdminRpc): com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse = throw UnsupportedOperationException()
        override suspend fun sendExternalToolResponse(command: com.letta.mobile.data.transport.appserver.AppServerCommand.ExternalToolCallResponse) {}
    }

    // ============ (d) LIFECYCLE — no ingestion after disconnect ========
    @Test
    fun observerStopsIngestingAfterDisconnect() = runBlocking {
        val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val transport = connectedTransport(stream)
        val frames = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            withTimeout(2_000) { while (transport.state.value !is com.letta.mobile.data.transport.ChannelTransportState.Connected) delay(10) }
            delay(100)

            // One frame while connected — ingested.
            stream.emit(streamDelta("agent-1", "conv-obs", 1, assistantDelta("cm-stream-live", "live")))
            withTimeout(3_000) { while (frames.none { it is ServerFrame.AssistantMessage && it.content == "live" }) delay(20) }

            // Disconnect stops the observer collector.
            transport.disconnect()
            delay(150)
            val before = frames.size

            // Frames after disconnect must NOT be ingested.
            stream.emit(streamDelta("agent-1", "conv-obs", 2, assistantDelta("cm-stream-dead", "dropped")))
            delay(300)

            assertEquals(before, frames.size, "no frames ingested after disconnect")
            assertTrue(frames.none { it is ServerFrame.AssistantMessage && it.content == "dropped" }, "post-disconnect frame dropped")
        } finally {
            collector.cancel()
        }
    }

    // ============ (c) REDUCER END-TO-END ==============================
    @Test
    fun observerEmittedFramesReduceToOneUserOneAssistantAndTerminal() = runBlocking {
        val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val transport = connectedTransport(stream)
        val frames = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            withTimeout(2_000) { while (transport.state.value !is com.letta.mobile.data.transport.ChannelTransportState.Connected) delay(10) }
            delay(100)

            stream.emit(streamDelta("agent-1", "conv-red", 1, userEchoDelta("otid-u", "the question")))
            stream.emit(streamDelta("agent-1", "conv-red", 2, assistantDelta("cm-stream-r", "Ans")))
            stream.emit(streamDelta("agent-1", "conv-red", 3, assistantDelta("cm-stream-r", "Answer done")))
            stream.emit(streamDelta("agent-1", "conv-red", 4, stopReasonDelta("end_turn")))

            withTimeout(5_000) { while (frames.none { it is ServerFrame.TurnDone }) delay(20) }
            collector.cancel()
            delay(50)
            val emitted = frames.toList()

            // Feed the OBSERVER-emitted ServerFrames through the real reducer path,
            // exactly as WsChatBridge does: ServerFrame -> LettaMessage -> reduce.
            var tl = com.letta.mobile.data.timeline.Timeline(conversationId = "conv-red")
            for (frame in emitted) {
                val msg = com.letta.mobile.data.transport.WsFrameMapper.toLettaMessage(frame) ?: continue
                tl = com.letta.mobile.data.timeline.reduceStreamFrame(
                    com.letta.mobile.data.timeline.TimelineReducerInput(
                        prev = tl,
                        frame = msg,
                        pendingToolReturnsByCallId = kotlinx.collections.immutable.persistentMapOf(),
                        source = "iroh-observer",
                    ),
                ).next
            }

            val userRows = tl.events.filter {
                it is com.letta.mobile.data.timeline.TimelineEvent.Confirmed &&
                    it.messageType == com.letta.mobile.data.timeline.TimelineMessageType.USER
            }
            val assistantRows = tl.events.filter {
                it is com.letta.mobile.data.timeline.TimelineEvent.Confirmed &&
                    it.messageType == com.letta.mobile.data.timeline.TimelineMessageType.ASSISTANT
            }
            assertEquals(1, userRows.size, "exactly one user row")
            assertEquals("the question", (userRows.single() as com.letta.mobile.data.timeline.TimelineEvent.Confirmed).content)
            assertEquals(1, assistantRows.size, "exactly one assistant row (cumulative deltas collapse)")
            assertEquals("Answer done", (assistantRows.single() as com.letta.mobile.data.timeline.TimelineEvent.Confirmed).content)
            // The user row precedes the assistant row.
            assertTrue(
                tl.events.indexOf(userRows.single()) < tl.events.indexOf(assistantRows.single()),
                "user row precedes assistant row",
            )
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }
}
