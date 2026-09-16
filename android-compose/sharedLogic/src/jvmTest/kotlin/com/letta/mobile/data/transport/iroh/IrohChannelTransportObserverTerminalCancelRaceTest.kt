package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Acceptance test for letta-mobile-53k65.5:
 * Exactly-once terminal arbitration in [IrohChannelTransport].
 *
 * Exercises:
 * 1. Delayed engine terminal: observer terminal arrives and claims terminal first; delayed engine terminal is safely skipped.
 * 2. Permanently absent engine terminal: observer terminal claims terminal; cancel does not synthesize duplicate.
 * 3. Repeated cancel and repeated terminal delivery: exactly 1 pre-dedupe terminal emitted across all sources.
 */
class IrohChannelTransportObserverTerminalCancelRaceTest {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun verifyDelayedEngineTerminalAfterObserverTerminalAndCancel(): Unit = scenarioTest {
        val turn = startTurn()
        assertTrue(transport.hasActiveChatTurn(CONVERSATION_ID))

        // Let the observer claim first, then deliver the late engine terminal and
        // a subsequent cancel. Neither loser may emit another terminal.
        emitTerminal(TerminalSource.Observer, turn.runId, seq = 5)
        awaitTurnDone()
        awaitInactiveTurn()
        assertFalse(transport.cancel(CONVERSATION_ID))
        emitTerminal(TerminalSource.Engine, turn.runId, seq = 6)
        releaseInput()
        assertDrained()

        val turnDones = frames.filterIsInstance<ServerFrame.TurnDone>()
        assertEquals(1, turnDones.size, "exactly 1 pre-dedupe terminal frame must be emitted: ${turnDones.map { it.status }}")
        val done = turnDones.single()
        assertEquals(turn.turnId, done.turnId)
        assertEquals("completed", done.status)
    }

    @Test
    fun verifyPermanentlyAbsentEngineTerminalRacingCancel(): Unit = scenarioTest {
        val turn = startTurn()

        // Cancel racing observer terminal while engine hangs
        assertTrue(transport.cancel(CONVERSATION_ID))
        emitTerminal(TerminalSource.Observer, turn.runId, seq = 5)

        awaitInactiveTurn()
        assertDrained()
        releaseInput()

        val turnDones = frames.filterIsInstance<ServerFrame.TurnDone>()
        assertEquals(1, turnDones.size, "exactly 1 pre-dedupe terminal frame must be emitted")
        val done = turnDones.single()
        assertEquals(turn.turnId, done.turnId)
    }

    @Test
    fun verifyRepeatedCancelAndRepeatedTerminalDelivery(): Unit = scenarioTest {
        val turn = startTurn()

        assertTrue(transport.cancel(CONVERSATION_ID))

        // Retire before retrying: a second cancel must not fabricate a terminal
        // after the original turn has been removed.
        emitTerminal(TerminalSource.Observer, turn.runId, seq = 10)
        emitTerminal(TerminalSource.Engine, turn.runId, seq = 11)
        releaseInput()
        awaitInactiveTurn()
        assertDrained()
        assertFalse(transport.cancel(CONVERSATION_ID))

        val turnDones = frames.filterIsInstance<ServerFrame.TurnDone>()
        assertEquals(1, turnDones.size, "exactly 1 pre-dedupe terminal frame must be emitted across all retries")
        val done = turnDones.single()
        assertEquals(turn.turnId, done.turnId)
    }

    @Test
    fun verifyDisconnectSettlesActiveTurnThroughTerminalGuard(): Unit = scenarioTest {
        val turn = startTurn()

        transport.disconnect()
        awaitTurnDone()
        assertDrained()
        releaseInput()

        val turnDones = frames.filterIsInstance<ServerFrame.TurnDone>()
        assertEquals(1, turnDones.size, "disconnect must settle its active turn exactly once")
        assertEquals(turn.turnId, turnDones.single().turnId)
        assertEquals("cancelled", turnDones.single().status)
    }

    private fun scenarioTest(block: suspend Scenario.() -> Unit): Unit = runBlocking {
        val scenario = createScenario()
        try {
            scenario.block()
        } finally {
            scenario.close()
        }
    }

    private suspend fun createScenario(): Scenario {
        val client = ControllableSplitClient()
        val observerStream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "runtime-start-1" },
        )
        val transport = IrohChannelTransport(
            scope = clientScope,
            activeConfigProvider = { IrohConnectConfig("iroh://ticket", "", "device", "test") },
            testDialer = { config ->
                IrohConnectionHandle(
                    config = config,
                    ticket = "ticket",
                    sessionId = "session-1",
                    turnEngine = engine,
                    observerStreamFrames = observerStream,
                    close = {},
                )
            },
            serverTerminalWaitMs = 150L,
        )
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val frameEvents = CopyOnWriteArrayList<TransportFrameEvent>()
        transport.connect("iroh://ticket", "", "device", "test")
        val eventsCollector = clientScope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect(frames::add)
        }
        val frameEventsCollector = clientScope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.frameEvents.collect(frameEvents::add)
        }
        withTimeout(5.seconds) {
            while (observerStream.subscriptionCount.value < 1) delay(10.milliseconds)
        }
        return Scenario(
            client,
            observerStream,
            transport,
            frames,
            frameEvents,
            listOf(eventsCollector, frameEventsCollector),
        )
    }

    private class Scenario(
        private val client: ControllableSplitClient,
        private val observerStream: MutableSharedFlow<AppServerReceivedFrame>,
        val transport: IrohChannelTransport,
        val frames: CopyOnWriteArrayList<ServerFrame>,
        private val frameEvents: CopyOnWriteArrayList<TransportFrameEvent>,
        private val collectors: List<Job>,
    ) {
        suspend fun startTurn(): ServerFrame.TurnStarted {
            assertTrue(transport.send(AGENT_ID, CONVERSATION_ID, "hello", "otid-1", null, false))
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.TurnStarted }) delay(10.milliseconds)
            }
            withTimeout(3.seconds) { client.inputEntered.await() }
            return frames.filterIsInstance<ServerFrame.TurnStarted>().single()
        }

        suspend fun emitTerminal(source: TerminalSource, runId: String, seq: Long) {
            when (source) {
                TerminalSource.Observer -> emitObserverStopReason(observerStream, CONVERSATION_ID, runId, seq)
                TerminalSource.Engine -> client.emitEngineStopReason(CONVERSATION_ID, runId, seq)
            }
        }

        suspend fun awaitInactiveTurn() {
            withTimeout(3.seconds) {
                while (transport.hasActiveChatTurn(CONVERSATION_ID)) delay(10.milliseconds)
            }
        }

        suspend fun awaitTurnDone() {
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.TurnDone }) delay(10.milliseconds)
            }
        }

        fun releaseInput() {
            client.releaseInput.complete(Unit)
        }

        suspend fun assertDrained() {
            assertFalse(transport.hasAnyActiveChatTurn)
            assertFalse(transport.hasActiveChatTurn(CONVERSATION_ID))
            withTimeout(3.seconds) {
                while (frames.map(ServerFrame::id) != frameEvents.map { it.frame.id }) {
                    delay(10.milliseconds)
                }
            }
            assertEquals(
                frames,
                frameEvents.map(TransportFrameEvent::frame),
                "events and frameEvents must remain coherent after terminal publication retires the turn",
            )
        }

        fun close() {
            collectors.forEach(Job::cancel)
            runBlocking { transport.disconnect() }
        }
    }

    private enum class TerminalSource {
        Observer,
        Engine,
    }

    private companion object {
        const val AGENT_ID = "agent-1"
        const val CONVERSATION_ID = "conv-1"

        suspend fun emitObserverStopReason(
            observerStream: MutableSharedFlow<AppServerReceivedFrame>,
            conversationId: String,
            runId: String,
            seq: Long,
        ) {
            val body = """
                {
                  "type": "stream_delta",
                  "runtime": {"agent_id": "$AGENT_ID", "conversation_id": "$conversationId"},
                  "event_seq": $seq,
                  "emitted_at": "2026-08-23T00:00:00Z",
                  "idempotency_key": "obs-$conversationId-$seq",
                  "delta": {"message_type": "stop_reason", "stop_reason": "end_turn", "run_id": "$runId"}
                }
            """.trimIndent()
            observerStream.emit(AppServerProtocol.decodeFrame(body, AppServerChannel.Stream))
        }
    }

    private class ControllableSplitClient : AppServerClient {
        private val engineStream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = engineStream

        val abortCommands = CopyOnWriteArrayList<AppServerCommand.AbortMessage>()

        val inputEntered = CompletableDeferred<Unit>()
        val releaseInput = CompletableDeferred<Unit>()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = command.agentId ?: AGENT_ID,
                    conversationId = requireNotNull(command.conversationId),
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) {
            inputEntered.complete(Unit)
            releaseInput.await()
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
            abortCommands.add(command)
            return AppServerInboundFrame.AbortMessageResponse(
                requestId = command.requestId ?: "",
                runtime = command.runtime,
                aborted = true,
                success = true,
            )
        }

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        suspend fun emitEngineStopReason(conversationId: String, runId: String, seq: Long) {
            val body = """
                {
                  "type": "stream_delta",
                  "runtime": {"agent_id": "$AGENT_ID", "conversation_id": "$conversationId"},
                  "event_seq": $seq,
                  "emitted_at": "2026-08-23T00:00:00Z",
                  "idempotency_key": "eng-$conversationId-$seq",
                  "delta": {"message_type": "stop_reason", "stop_reason": "end_turn", "run_id": "$runId"}
                }
            """.trimIndent()
            engineStream.emit(AppServerProtocol.decodeFrame(body, AppServerChannel.Stream))
        }
    }
}
