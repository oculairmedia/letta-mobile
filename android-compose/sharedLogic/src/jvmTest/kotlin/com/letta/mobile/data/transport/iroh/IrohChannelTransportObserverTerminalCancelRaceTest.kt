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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private suspend fun transportWith(
        client: ControllableSplitClient,
        observerStream: MutableSharedFlow<AppServerReceivedFrame>,
    ): Pair<IrohChannelTransport, AppServerTurnEngine> {
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
        transport.connect("iroh://ticket", "", "device", "test")
        withTimeout(5.seconds) {
            while (observerStream.subscriptionCount.value < 1) delay(10.milliseconds)
        }
        return transport to engine
    }

    @Test
    fun characterizeDelayedEngineTerminalAfterObserverTerminalAndCancel(): Unit = runBlocking {
        val client = ControllableSplitClient()
        val observerStream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val (transport, _) = transportWith(client, observerStream)
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }

        try {
            delay(150.milliseconds)

            // 1. Start turn on conv-1
            assertTrue(transport.send(AGENT, CONV_1, "hello", "otid-1", null, false))
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.TurnStarted }) delay(10.milliseconds)
            }
            withTimeout(3.seconds) { client.inputEntered.await() }

            val turnSnapshot = transport.activeTurnSnapshot(CONV_1)
            assertNotNull(turnSnapshot)
            val turnId = turnSnapshot.turnId
            val runId = turnSnapshot.runId
            assertTrue(transport.hasActiveChatTurn(CONV_1))

            // 2. Observer projects a TurnDone via stop_reason
            emitObserverStopReason(observerStream, CONV_1, runId, seq = 5)

            // Within observer cycle, active turn is retired from activeTurns map
            withTimeout(3.seconds) {
                while (transport.hasActiveChatTurn(CONV_1)) delay(10.milliseconds)
            }
            assertNull(transport.activeTurnSnapshot(CONV_1), "active turn retired by observer")

            // 3. Concurrent cancel arrives
            val cancelResult = transport.cancel(CONV_1)
            assertTrue(cancelResult)

            // 4. Delayed engine terminal arrives after send job was cancelled by cancel()
            client.emitEngineStopReason(CONV_1, runId, seq = 6)
            client.releaseInput.complete(Unit)

            withTimeout(3.seconds) {
                while (frames.filterIsInstance<ServerFrame.TurnDone>().isEmpty()) delay(10.milliseconds)
            }
            delay(200.milliseconds)

            // Final state: clean maps
            assertEquals(0, transport.activeTurnsCount())
            assertEquals(0, transport.activeSendJobsCount())
            assertFalse(transport.hasActiveChatTurn(CONV_1))

            val allTurnDones = frames.filterIsInstance<ServerFrame.TurnDone>()
            val turnDoneSummary = allTurnDones.joinToString { "${it.turnId}:${it.status}" }
            assertTrue(allTurnDones.isNotEmpty(), "expected terminal TurnDone frames: $turnDoneSummary")
            val doneCancelled = allTurnDones.firstOrNull { it.status == "cancelled" }
            assertNotNull(doneCancelled, "cancelled TurnDone must be emitted ($turnDoneSummary)")
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    @Test
    fun characterizePermanentlyAbsentEngineTerminalRacingCancel(): Unit = runBlocking {
        val client = ControllableSplitClient()
        val observerStream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val (transport, _) = transportWith(client, observerStream)
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }

        try {
            delay(150.milliseconds)

            // 1. Start turn on conv-1
            assertTrue(transport.send(AGENT, CONV_1, "hello", "otid-1", null, false))
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.TurnStarted }) delay(10.milliseconds)
            }
            withTimeout(3.seconds) { client.inputEntered.await() }

            val turnSnapshot = transport.activeTurnSnapshot(CONV_1)
            assertNotNull(turnSnapshot)
            val turnId = turnSnapshot.turnId
            val runId = turnSnapshot.runId

            // 2. Observer receives terminal
            emitObserverStopReason(observerStream, CONV_1, runId, seq = 5)
            withTimeout(3.seconds) {
                while (transport.hasActiveChatTurn(CONV_1)) delay(10.milliseconds)
            }

            // 3. Cancel arrives while engine terminal is withheld
            assertTrue(transport.cancel(CONV_1))

            // Wait for synthetic cancel fallback
            delay(300.milliseconds)
            client.releaseInput.complete(Unit)

            // Final state assertions
            assertEquals(0, transport.activeTurnsCount())
            assertEquals(0, transport.activeSendJobsCount())
            assertFalse(transport.hasActiveChatTurn(CONV_1))
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    @Test
    fun characterizeRepeatedCancelAndRepeatedTerminalDelivery(): Unit = runBlocking {
        val client = ControllableSplitClient()
        val observerStream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val (transport, _) = transportWith(client, observerStream)
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }

        try {
            delay(150.milliseconds)

            assertTrue(transport.send(AGENT, CONV_1, "hello", "otid-1", null, false))
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.TurnStarted }) delay(10.milliseconds)
            }
            withTimeout(3.seconds) { client.inputEntered.await() }

            val turnSnapshot = transport.activeTurnSnapshot(CONV_1)
            assertNotNull(turnSnapshot)
            val turnId = turnSnapshot.turnId
            val runId = turnSnapshot.runId

            // Deliver observer terminal
            emitObserverStopReason(observerStream, CONV_1, runId, seq = 10)
            withTimeout(3.seconds) {
                while (transport.hasActiveChatTurn(CONV_1)) delay(10.milliseconds)
            }

            // Repeated cancel invocations
            assertTrue(transport.cancel(CONV_1))
            assertTrue(transport.cancel(CONV_1))

            // Deliver engine terminal and extra duplicate observer terminal
            client.emitEngineStopReason(CONV_1, runId, seq = 11)
            emitObserverStopReason(observerStream, CONV_1, runId, seq = 12)
            client.releaseInput.complete(Unit)

            delay(300.milliseconds)

            // Maps cleanly drained
            assertEquals(0, transport.activeTurnsCount())
            assertEquals(0, transport.activeSendJobsCount())
            assertFalse(transport.hasActiveChatTurn(CONV_1))
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV_1 = "conv-1"

        suspend fun emitObserverStopReason(
            observerStream: MutableSharedFlow<AppServerReceivedFrame>,
            conversationId: String,
            runId: String,
            seq: Long,
        ) {
            val body = """
                {
                  "type": "stream_delta",
                  "runtime": {"agent_id": "$AGENT", "conversation_id": "$conversationId"},
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
                    agentId = command.agentId ?: AGENT,
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
                  "runtime": {"agent_id": "$AGENT", "conversation_id": "$conversationId"},
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
