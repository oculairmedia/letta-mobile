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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Acceptance test for letta-mobile-53k65.4:
 * Atomic same-conversation supersession and rejection lifecycle in [IrohChannelTransport].
 *
 * Sequence under test:
 * 1. First turn starts and streams input on (agent-1, conv-1).
 * 2. Second send arrives for the SAME conversation while the first is in-flight.
 * 3. Atomic registration rejects the second send as busy without displacing turn #1.
 * 4. Turn #1 ownership, active job reachability, cancellation targeting, and liveness
 *    are preserved until turn #1 settles.
 */
class IrohChannelTransportSameConversationSupersessionTest {

    private val clientScope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun transportWith(client: ScriptedClient): IrohChannelTransport {
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "runtime-start-1" },
        )
        return IrohChannelTransport(
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
    }

    @Test
    fun verifySameConversationAtomicRejectionPreservesActiveTurn(): Unit = runBlocking {
        val client = ScriptedClient()
        val transport = transportWith(client)
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }

        try {
            delay(150.milliseconds)
            val firstTurn = startFirstTurn(transport, client, frames)
            val secondTurnId = rejectSecondTurn(transport, frames)

            assertFirstTurnOwnershipIsPreserved(transport, firstTurn.id)
            cancelConversation(transport, client, firstTurn.id)
            completeFirstTurn(client, frames, firstTurn)
            assertFinalState(transport, frames, firstTurn.id, secondTurnId)
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    private suspend fun startFirstTurn(
        transport: IrohChannelTransport,
        client: ScriptedClient,
        frames: List<ServerFrame>,
    ): RunningTurn {
        transport.send(AGENT, CONV_1, "first-message", "otid-1", null, false)
        withTimeout(3.seconds) { client.firstInputEntered.await() }
        withTimeout(3.seconds) {
            while (frames.none { it is ServerFrame.TurnStarted }) delay(10.milliseconds)
        }

        val started = frames.filterIsInstance<ServerFrame.TurnStarted>().single()
        val job = assertNotNull(
            transport.privateMap<Job>("activeSendJobs")[CONV_1],
            "job for turn #1 must be registered in activeSendJobs",
        )
        assertEquals(
            started.turnId,
            transport.activeTurnId(CONV_1),
            "active turn #1 must be registered in activeTurns",
        )
        assertTrue(job.isActive, "job for turn #1 must be active")
        assertTrue(transport.hasActiveChatTurn(CONV_1), "transport must report active chat turn for conv-1")
        return RunningTurn(started.turnId, started.runId, job)
    }

    private suspend fun rejectSecondTurn(
        transport: IrohChannelTransport,
        frames: List<ServerFrame>,
    ): String {
        transport.send(AGENT, CONV_1, "second-message", "otid-2", null, false)
        withTimeout(3.seconds) {
            while (frames.none { it is ServerFrame.Error && it.code == "iroh_turn_engine_busy" }) delay(10.milliseconds)
        }
        withTimeout(3.seconds) {
            while (frames.none { it is ServerFrame.TurnDone && it.status == "failed" }) delay(10.milliseconds)
        }
        val busyError = frames.filterIsInstance<ServerFrame.Error>().single { it.code == "iroh_turn_engine_busy" }
        delay(50.milliseconds)
        return assertNotNull(busyError.turnId, "busy rejection must identify turn #2")
    }

    private fun assertFirstTurnOwnershipIsPreserved(transport: IrohChannelTransport, firstTurnId: String) {
        assertEquals(firstTurnId, transport.activeTurnId(CONV_1), "activeTurns must belong to turn #1")
        val job = transport.privateMap<Job>("activeSendJobs")[CONV_1]
        assertNotNull(job, "activeSendJobs[CONV_1] must remain populated with turn #1's job")
        assertTrue(job.isActive, "turn #1's job must remain active")
        assertTrue(
            transport.hasActiveChatTurn(CONV_1),
            "hasActiveChatTurn must remain true while turn #1 is streaming",
        )
    }

    private suspend fun cancelConversation(
        transport: IrohChannelTransport,
        client: ScriptedClient,
        firstTurnId: String,
    ) {
        assertEquals(firstTurnId, transport.activeTurnId(CONV_1), "cancel must target turn #1")
        val cancelResult = transport.cancel(CONV_1)
        assertTrue(cancelResult, "cancel returns true")
        withTimeout(3.seconds) {
            while (client.abortCommands.isEmpty()) delay(10.milliseconds)
        }
        assertEquals(1, client.abortCommands.size, "abort_message was correctly sent targeting turn #1")
        val abortCmd = client.abortCommands.single()
        assertEquals(CONV_1, abortCmd.runtime.conversationId)
        assertEquals(AGENT, abortCmd.runtime.agentId)
    }

    private suspend fun completeFirstTurn(
        client: ScriptedClient,
        frames: List<ServerFrame>,
        firstTurn: RunningTurn,
    ) {
        client.emitStopReason(CONV_1, firstTurn.runId, seq = 2)
        client.releaseFirstInput.complete(Unit)
        withTimeout(3.seconds) {
            while (frames.filterIsInstance<ServerFrame.TurnDone>().none { it.turnId == firstTurn.id }) {
                delay(10.milliseconds)
            }
        }
        firstTurn.job.join()
        delay(50.milliseconds)
    }

    private fun assertFinalState(
        transport: IrohChannelTransport,
        frames: List<ServerFrame>,
        firstTurnId: String,
        secondTurnId: String,
    ) {
        assertEquals(0, transport.privateMap<Any>("activeTurns").size, "activeTurns map must be empty after all turns complete")
        assertEquals(0, transport.privateMap<Job>("activeSendJobs").size, "activeSendJobs map must be empty after all turns complete")
        assertFalse(transport.hasActiveChatTurn(CONV_1), "hasActiveChatTurn must be false after completion")

        val turnDoneFrames = frames.filterIsInstance<ServerFrame.TurnDone>()
        val doneForTurn1 = turnDoneFrames.filter { it.turnId == firstTurnId }
        val doneForTurn2 = turnDoneFrames.filter { it.turnId == secondTurnId }

        assertEquals(1, doneForTurn1.size, "turn #1 must receive exactly 1 terminal frame")
        assertEquals(1, doneForTurn2.size, "turn #2 must receive exactly 1 terminal frame (its busy rejection)")
        assertEquals("failed", doneForTurn2.single().status)
    }

    private fun IrohChannelTransport.activeTurnId(conversationId: String): String? {
        val turn = privateMap<Any>("activeTurns")[conversationId] ?: return null
        val field = turn.javaClass.getDeclaredField("turnId")
        field.isAccessible = true
        return field.get(turn) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun <V> IrohChannelTransport.privateMap(fieldName: String): Map<String, V> {
        val field = IrohChannelTransport::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(this) as Map<String, V>
    }

    private data class RunningTurn(
        val id: String,
        val runId: String,
        val job: Job,
    )

    private companion object {
        const val AGENT = "agent-1"
        const val CONV_1 = "conv-1"
    }

    private class ScriptedClient : AppServerClient {
        val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = stream

        val firstInputEntered = CompletableDeferred<Unit>()
        val releaseFirstInput = CompletableDeferred<Unit>()

        val abortCommands = CopyOnWriteArrayList<AppServerCommand.AbortMessage>()

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
            firstInputEntered.complete(Unit)
            releaseFirstInput.await()
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

        suspend fun emitStopReason(conversationId: String, runId: String, seq: Long) {
            val body = """
                {
                  "type": "stream_delta",
                  "runtime": {"agent_id": "$AGENT", "conversation_id": "$conversationId"},
                  "event_seq": $seq,
                  "emitted_at": "2026-08-23T00:00:00Z",
                  "idempotency_key": "evt-$conversationId-$seq",
                  "delta": {"message_type": "stop_reason", "stop_reason": "end_turn", "run_id": "$runId"}
                }
            """.trimIndent()
            stream.emit(AppServerProtocol.decodeFrame(body, AppServerChannel.Stream))
        }
    }
}
