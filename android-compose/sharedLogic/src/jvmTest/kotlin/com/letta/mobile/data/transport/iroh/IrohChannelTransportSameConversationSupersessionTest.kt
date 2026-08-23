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
 * Characterization test for letta-mobile-53k65.1:
 * Same-conversation supersession lifecycle in [IrohChannelTransport].
 *
 * Sequence under test:
 * 1. First turn starts and streams input on (agent-1, conv-1).
 * 2. Second send arrives for the SAME conversation while the first is in-flight.
 * 3. The turn engine rejects the second send as busy.
 * 4. Demonstrates the ownership, active job reachability, cancellation targeting,
 *    and liveness transitions across the lifecycle of both turns.
 */
class IrohChannelTransportSameConversationSupersessionTest {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun transportWith(client: ScriptedClient): Pair<IrohChannelTransport, AppServerTurnEngine> {
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
                    close = {},
                )
            },
            serverTerminalWaitMs = 100L,
        )
        return transport to engine
    }

    @Test
    fun characterizeSameConversationSupersessionAndRejectionLifecycle() = runBlocking {
        val client = ScriptedClient()
        val (transport, engine) = transportWith(client)
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }

        try {
            // Let the events collector subscribe
            delay(150.milliseconds)

            // 1. Start send #1
            transport.send(AGENT, CONV_1, "first-message", "otid-1", null, false)

            // Await first turn input entered in engine
            withTimeout(3.seconds) { client.firstInputEntered.await() }
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.TurnStarted }) delay(10.milliseconds)
            }

            val turn1Snapshot = transport.activeTurnSnapshot(CONV_1)
            assertNotNull(turn1Snapshot, "active turn #1 must be registered in activeTurns")
            val turn1Id = turn1Snapshot.turnId
            val turn1RunId = turn1Snapshot.runId
            val job1 = transport.activeSendJob(CONV_1)
            assertNotNull(job1, "job for turn #1 must be registered in activeSendJobs")
            assertTrue(job1.isActive, "job for turn #1 must be active")
            assertTrue(transport.hasActiveChatTurn(CONV_1), "transport must report active chat turn for conv-1")

            // 2. Invoke send #2 with SAME conversation key while #1 is streaming
            transport.send(AGENT, CONV_1, "second-message", "otid-2", null, false)

            // Wait for busy rejection on send #2
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.Error && it.code == "iroh_turn_engine_busy" }) delay(10.milliseconds)
            }
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.TurnDone && it.status == "failed" }) delay(10.milliseconds)
            }

            val busyError = frames.filterIsInstance<ServerFrame.Error>().single { it.code == "iroh_turn_engine_busy" }
            val turn2Id = busyError.turnId

            // Allow sendJob2 completion callback to execute
            delay(50.milliseconds)

            // Characterize current-main reachability:
            // On current main, send #2 evicted turn1 from activeTurns & activeSendJobs,
            // and sendJob2 completion removed activeTurns[CONV_1] and activeSendJobs[CONV_1].
            val postRejectSnapshot = transport.activeTurnSnapshot(CONV_1)
            val postRejectJob = transport.activeSendJob(CONV_1)
            assertNull(postRejectSnapshot, "characterization: on current-main, activeTurns[CONV_1] is evicted and cleared by send #2 completion")
            assertNull(postRejectJob, "characterization: on current-main, activeSendJobs[CONV_1] is cleared by send #2 completion")
            assertFalse(transport.hasActiveChatTurn(CONV_1), "characterization: hasActiveChatTurn is prematurely false while turn #1 is still streaming")

            // 3. Attempt to cancel CONV_1
            val cancelResult = transport.cancel(CONV_1)
            assertTrue(cancelResult, "cancel returns true by synthesizing a fallback cancelled frame")
            assertTrue(client.abortCommands.isEmpty(), "characterization: no abort_message sent to server because activeTurns was empty")

            // 4. Now complete turn #1 via late stop_reason from the server stream
            client.emitStopReason(CONV_1, turn1RunId, seq = 2)
            client.releaseFirstInput.complete(Unit)

            withTimeout(3.seconds) {
                while (frames.filterIsInstance<ServerFrame.TurnDone>().none { it.turnId == turn1Id }) delay(10.milliseconds)
            }

            // Wait for turn #1 job completion
            job1.join()
            delay(50.milliseconds)

            // 5. Final state assertions:
            assertEquals(0, transport.activeTurnsCount(), "activeTurns map must be empty after all turns complete")
            assertEquals(0, transport.activeSendJobsCount(), "activeSendJobs map must be empty after all jobs complete")
            assertFalse(transport.hasActiveChatTurn(CONV_1), "no active chat turn at the end")

            // Exact pre-dedupe frame sequence verification:
            val allTurnStarts = frames.filterIsInstance<ServerFrame.TurnStarted>()
            assertEquals(1, allTurnStarts.size, "only turn #1 had TurnStarted emitted; rejected turn #2 did not")
            assertEquals(turn1Id, allTurnStarts.single().turnId)

            val allTurnDones = frames.filterIsInstance<ServerFrame.TurnDone>()
            val turnDoneSummary = allTurnDones.joinToString { "${it.turnId}:${it.status}" }
            assertEquals(3, allTurnDones.size, "actual TurnDones: $turnDoneSummary")
            val done1 = allTurnDones.firstOrNull { it.turnId == turn1Id }
            val done2 = allTurnDones.firstOrNull { it.turnId == turn2Id }
            val doneCancelled = allTurnDones.firstOrNull { it.status == "cancelled" }
            val doneObserver = allTurnDones.firstOrNull { it.turnId == "iroh-observer-turn-$CONV_1" }
            assertNotNull(done1, "turn #1 engine TurnDone must be present ($turnDoneSummary)")
            assertNotNull(done2, "turn #2 failed TurnDone must be present ($turnDoneSummary)")
            assertTrue(
                doneCancelled != null || doneObserver != null,
                "expected either cancelled or observer TurnDone ($turnDoneSummary)",
            )
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV_1 = "conv-1"
    }

    private class ScriptedClient : AppServerClient {
        val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = stream

        val subscriberCount: Int get() = stream.subscriptionCount.value

        val abortCommands = CopyOnWriteArrayList<AppServerCommand.AbortMessage>()

        val firstInputEntered = CompletableDeferred<Unit>()
        val releaseFirstInput = CompletableDeferred<Unit>()

        private var inputCount = 0

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
            val idx = ++inputCount
            if (idx == 1) {
                firstInputEntered.complete(Unit)
                releaseFirstInput.await()
            } else {
                awaitCancellation()
            }
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

        suspend fun emitStopReason(conversationId: String, runId: String, seq: Long) =
            emit(conversationId, seq, runId, """{"message_type": "stop_reason", "stop_reason": "end_turn", "run_id": "$runId"}""")

        private suspend fun emit(conversationId: String, seq: Long, runId: String, delta: String) {
            val body = """
                {
                  "type": "stream_delta",
                  "runtime": {"agent_id": "$AGENT", "conversation_id": "$conversationId"},
                  "event_seq": $seq,
                  "emitted_at": "2026-08-23T00:00:00Z",
                  "idempotency_key": "evt-$conversationId-$seq",
                  "delta": $delta
                }
            """.trimIndent()
            stream.emit(AppServerProtocol.decodeFrame(body, AppServerChannel.Stream))
        }
    }
}
