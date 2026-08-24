package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.AppServerTurnEngine
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Characterization test for letta-mobile-53k65.2:
 * Observer terminal versus concurrent cancel race in [IrohChannelTransport].
 *
 * Exercises:
 * 1. Delayed engine terminal: observer terminal arrives and retires active turn before engine terminal.
 * 2. Permanently absent engine terminal: observer terminal arrives while engine hangs, racing cancel.
 * 3. Repeated cancel and repeated terminal delivery: ensures idempotent terminal and clean final snapshots.
 */
class IrohChannelTransportObserverTerminalCancelRaceTest {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun characterizeDelayedEngineTerminalAfterObserverTerminalAndCancel() = scenarioTest {
        val turn = startTurn()
        assertTrue(transport.hasActiveChatTurn(CONVERSATION_ID))

        emitTerminal(TerminalSource.Observer, turn.runId, seq = 5)
        awaitInactiveTurn()
        assertTrue(transport.cancel(CONVERSATION_ID))

        emitTerminal(TerminalSource.Engine, turn.runId, seq = 6)
        releaseInput()
        awaitTurnDone()
        delay(200.milliseconds)
        assertDrained()

        val turnDones = frames.filterIsInstance<ServerFrame.TurnDone>()
        val summary = turnDones.joinToString { "${it.turnId}:${it.status}" }
        assertTrue(turnDones.isNotEmpty(), "expected terminal TurnDone frames: $summary")
        assertNotNull(
            turnDones.firstOrNull { it.status == "cancelled" },
            "cancelled TurnDone must be emitted ($summary)",
        )
    }

    @Test
    fun characterizePermanentlyAbsentEngineTerminalRacingCancel() = scenarioTest {
        val turn = startTurn()

        emitTerminal(TerminalSource.Observer, turn.runId, seq = 5)
        awaitInactiveTurn()
        assertTrue(transport.cancel(CONVERSATION_ID))

        delay(300.milliseconds)
        releaseInput()
        assertDrained()
    }

    @Test
    fun characterizeRepeatedCancelAndRepeatedTerminalDelivery() = scenarioTest {
        val turn = startTurn()

        emitTerminal(TerminalSource.Observer, turn.runId, seq = 10)
        awaitInactiveTurn()
        assertTrue(transport.cancel(CONVERSATION_ID))
        assertTrue(transport.cancel(CONVERSATION_ID))

        emitTerminal(TerminalSource.Engine, turn.runId, seq = 11)
        emitTerminal(TerminalSource.Observer, turn.runId, seq = 12)
        releaseInput()
        delay(300.milliseconds)
        assertDrained()
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
        transport.connect("iroh://ticket", "", "device", "test")
        val collector = clientScope.launch { transport.events.collect(frames::add) }
        withTimeout(5.seconds) {
            while (observerStream.subscriptionCount.value < 1) delay(10.milliseconds)
        }
        delay(150.milliseconds)
        return Scenario(client, observerStream, transport, frames, collector)
    }

    private class Scenario(
        private val client: ControllableSplitClient,
        private val observerStream: MutableSharedFlow<AppServerReceivedFrame>,
        val transport: IrohChannelTransport,
        val frames: CopyOnWriteArrayList<ServerFrame>,
        private val collector: Job,
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
            val target = when (source) {
                TerminalSource.Observer -> observerStream
                TerminalSource.Engine -> client.engineStream
            }
            target.emit(stopReasonFrame(source.idPrefix, runId, seq))
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
            withTimeout(3.seconds) {
                while (transport.hasActiveChatTurn(CONVERSATION_ID) || transport.hasAnyActiveChatTurn) {
                    delay(10.milliseconds)
                }
            }
            assertFalse(transport.hasActiveChatTurn(CONVERSATION_ID))
            assertFalse(transport.hasAnyActiveChatTurn)
        }

        suspend fun close() {
            collector.cancel()
            transport.disconnect()
        }
    }

    private enum class TerminalSource(val idPrefix: String) {
        Observer("obs"),
        Engine("eng"),
    }

    private class ControllableSplitClient : AppServerClient {
        val engineStream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
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
                requestId = command.requestId.orEmpty(),
                runtime = command.runtime,
                aborted = true,
                success = true,
            )
        }

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }

    private companion object {
        const val AGENT_ID = "agent-1"
        const val CONVERSATION_ID = "conv-1"

        fun stopReasonFrame(idPrefix: String, runId: String, seq: Long): AppServerReceivedFrame {
            val body = """
                {
                  "type": "stream_delta",
                  "runtime": {"agent_id": "$AGENT_ID", "conversation_id": "$CONVERSATION_ID"},
                  "event_seq": $seq,
                  "emitted_at": "2026-08-23T00:00:00Z",
                  "idempotency_key": "$idPrefix-$CONVERSATION_ID-$seq",
                  "delta": {"message_type": "stop_reason", "stop_reason": "end_turn", "run_id": "$runId"}
                }
            """.trimIndent()
            return AppServerProtocol.decodeFrame(body, AppServerChannel.Stream)
        }
    }
}
