package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.48: on Iroh, a relayed turn's `turn_finished` cannot overtake the deltas it
 * closes, because both ride the SAME QUIC stream.
 *
 * The node writes every frame of a relayed turn (`stream_delta`, `update_loop_status`,
 * `update_queue`, `turn_finished`) to the initiator's stream bi-stream through one
 * [IrohViewerHandle], on one per-viewer [ObserverWriteQueue] chain, stamping `event_seq` inside
 * that chain. The control bi-stream carries only request answers and `input_accepted`, which has
 * no `event_seq`. On the phone, `IrohAppServerTransport` reads each QUIC stream with one reader
 * into one `SharedFlow`, and `mergedFrames()` collects each lane with one collector, so the order
 * within the stream lane survives the (unordered) merge of the two lanes.
 *
 * The overtake fixed for the Ktor socket in by4vm.5 came from splitting ONE ordered socket into
 * two lanes. Iroh has no such split for turn frames, so it needs no reorder window. These tests
 * pin that: if the node ever moves a turn frame to the control stream, the first test fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohRelayedTurnLaneOrderTest {

    @Test
    fun turnFinishedRidesTheStreamChannelBehindEveryDeltaItCloses() = runTest {
        val phone = relayTurn(TURN)

        assertEquals(listOf("input_accepted"), phone.control.map { it.type }, "control carries only the ack")
        assertNull(phone.control.single().json["event_seq"], "input_accepted is outside the event_seq order")
        val stream = phone.stream
        assertEquals("turn_finished", stream.last().type, "turn_finished closes the stream channel: ${stream.map { it.kind }}")
        assertEquals(DELTA_COUNT, stream.count { it.kind == "assistant_message" }, "every assistant delta is on the stream channel")
        val seqs = stream.map { it.eventSeq() }
        assertEquals((seqs.first() until seqs.first() + seqs.size).toList(), seqs, "one contiguous event_seq in wire order")
    }

    @Test
    fun phoneSeesTrailingDeltasBeforeTurnFinishedWhenTheControlStreamIsReadLast() = runTest {
        val phone = relayTurn(TURN)

        val seen = deliverToTurnSubscriber(phone, controlFirst = false)

        assertTurnSubscriberSawWholeTurnInOrder(phone, seen)
    }

    @Test
    fun phoneSeesTrailingDeltasBeforeTurnFinishedWhenTheControlStreamIsReadFirst() = runTest {
        val phone = relayTurn(TURN)

        val seen = deliverToTurnSubscriber(phone, controlFirst = true)

        assertTurnSubscriberSawWholeTurnInOrder(phone, seen)
    }

    @Test
    fun lateEngineDeltasStillLandAheadOfTurnFinishedOnTheSameStream() = runTest {
        // qygvv.18 race: the engine yields deltas after its terminal lifecycle draft.
        val phone = relayTurn(listOf(assistant("Hel"), completed(), assistant("Hello"), assistant("Hello!")))

        val seen = deliverToTurnSubscriber(phone, controlFirst = false)

        assertEquals(3, seen.count { it.isAssistantDelta() }, "no trailing delta is dropped")
        assertEquals("turn_finished", seen.last().frame.type)
        assertEquals(seen.map { it.eventSeqOrFail() }.sorted(), seen.map { it.eventSeqOrFail() })
    }

    private fun assertTurnSubscriberSawWholeTurnInOrder(phone: FakePhoneLink, seen: List<AppServerReceivedFrame>) {
        val sent = phone.stream.map { it.eventSeq() }
        assertEquals(sent, seen.map { it.eventSeqOrFail() }, "the subscriber sees the stream channel in wire order")
        assertEquals("turn_finished", seen.last().frame.type)
        assertEquals(DELTA_COUNT, seen.count { it.isAssistantDelta() }, "no trailing delta is dropped")
        assertTrue(seen.all { it.channel == AppServerChannel.Stream }, "every turn frame arrived on the stream channel")
    }

    /** Relays [drafts] through the node's real fanout and protocol into a fake phone. */
    private suspend fun TestScope.relayTurn(drafts: List<RuntimeEventDraft>): FakePhoneLink {
        val phone = FakePhoneLink(runtime, CLIENT_MESSAGE_ID, requestId = "req-lanes", writeScope = backgroundScope)
        phone.relay(controllerRunning { flow { drafts.forEach { emit(it) } } }, turnCommandFor(runtime, CLIENT_MESSAGE_ID))
        testScheduler.advanceUntilIdle()
        return phone
    }

    /**
     * Replays what [phone] captured into a transport shaped like `IrohAppServerTransport` (one
     * reader and one SharedFlow per QUIC stream, the default `inboundFrames`), behind the real
     * router, to a subscriber that stops at `turn_finished` like the turn engine. [controlFirst]
     * picks which stream's reader runs first; the other runs only after it drained.
     */
    private suspend fun TestScope.deliverToTurnSubscriber(
        phone: FakePhoneLink,
        controlFirst: Boolean,
    ): List<AppServerReceivedFrame> {
        val transport = TwoStreamTransport()
        val router = AppServerRuntimeEventRouter()
        router.attach(backgroundScope, transport.inboundFrames)
        val (_, events) = router.subscribe(AgentId(runtime.agentId), ConversationId(runtime.conversationId))
        val turn = async { events.untilTurnFinished().toList() }
        runCurrent()

        val controlReader = { launch { phone.control.forEach { transport.receive(AppServerChannel.Control, it) } } }
        val streamReader = { launch { phone.stream.forEach { transport.receive(AppServerChannel.Stream, it) } } }
        if (controlFirst) controlReader().join() else streamReader().join()
        if (controlFirst) streamReader().join() else controlReader().join()
        runCurrent()

        return turn.await().filter { it.frame.hasEventSeq() }
    }

    /** The lane structure of `IrohAppServerTransport`, without the QUIC connection. */
    private class TwoStreamTransport : AppServerTransport {
        private val control = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
        private val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
        override val controlFrames: Flow<AppServerReceivedFrame> = control.asSharedFlow()
        override val streamFrames: Flow<AppServerReceivedFrame> = stream.asSharedFlow()

        override suspend fun sendControl(command: AppServerCommand) = Unit

        /** One reader-loop step: decode with the physical stream's label, as the transport does. */
        suspend fun receive(channel: AppServerChannel, frame: WireFrame) {
            val decoded = AppServerProtocol.decodeFrame(frame.json.toString(), channel)
            if (channel == AppServerChannel.Control) control.emit(decoded) else stream.emit(decoded)
        }
    }

    private fun Flow<AppServerReceivedFrame>.untilTurnFinished(): Flow<AppServerReceivedFrame> =
        transformWhile {
            emit(it)
            it.frame !is AppServerInboundFrame.TurnFinished
        }

    private fun WireFrame.eventSeq(): Long =
        requireNotNull((json["event_seq"] as? JsonPrimitive)?.longOrNull) { "no event_seq on $type" }

    private fun AppServerInboundFrame.hasEventSeq(): Boolean =
        this is AppServerInboundFrame.StreamDelta ||
            this is AppServerInboundFrame.UpdateLoopStatus ||
            this is AppServerInboundFrame.TurnFinished

    private fun AppServerReceivedFrame.eventSeqOrFail(): Long = when (val f = frame) {
        is AppServerInboundFrame.StreamDelta -> f.eventSeq
        is AppServerInboundFrame.UpdateLoopStatus -> f.eventSeq
        is AppServerInboundFrame.TurnFinished -> f.eventSeq
        else -> error("no event_seq on ${f.type}")
    }

    private fun AppServerReceivedFrame.isAssistantDelta(): Boolean =
        raw?.get("delta")?.let { delta ->
            ((delta as? JsonObject)?.get("message_type") as? JsonPrimitive)?.content == "assistant_message"
        } == true

    private companion object {
        const val CLIENT_MESSAGE_ID = "cm-lanes"
        const val FRAME_BUFFER_CAPACITY = 64
        const val DELTA_COUNT = 3
        val runtime = AppServerRuntimeScope("agent-lanes", "conv-lanes")
        var nextFrame = 0L

        val TURN: List<RuntimeEventDraft>
            get() = listOf(assistant("Hel"), assistant("Hello"), assistant("Hello!"), completed())

        fun draft(payload: RuntimeEventPayload) = RuntimeEventDraft(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:${runtime.agentId}:${runtime.conversationId}"),
            agentId = AgentId(runtime.agentId),
            conversationId = ConversationId(runtime.conversationId),
            runId = RunId("run-lanes"),
            source = RuntimeEventSource.RemoteLetta,
            payload = payload,
        )

        fun assistant(content: String): RuntimeEventDraft {
            nextFrame += 1
            val body = buildJsonObject {
                put("type", "stream_delta")
                put("event_seq", nextFrame)
                put("idempotency_key", "lanes-$nextFrame")
                put(
                    "delta",
                    buildJsonObject {
                        put("message_type", "assistant_message")
                        put("otid", "otid-lanes")
                        put("id", "letta-msg-lanes")
                        put("content", content)
                    },
                )
            }.toString()
            return draft(RuntimeEventPayload.RemoteStreamFrame(frameId = "lanes-$nextFrame", body = body))
        }

        fun completed() = draft(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed, reason = "end_turn"))
    }
}
