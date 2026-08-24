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
import kotlinx.coroutines.CoroutineStart
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-dir4k — observer-path TurnDone retires the initiator's ActiveTurn.
 *
 * Reported symptom: after a server turn completed and fanout reached the user's
 * chat surface, the composer still showed "Thinking…" + the red cancel button.
 * The projection correctly folded the run disclosure to "Worked for 5.0s"
 * (proving the projection knows the turn is done), but `hasActiveChatTurn`
 * stayed true — `activeTurns` still held the [ActiveTurn] entry because the
 * exactly-one-terminal guard had only ever completed `terminalReached`, never
 * removed the entry. The pre-fix code relied on `sendJob.invokeOnCompletion` to
 * remove the entry, which never fired when the engine's collect was cancelled
 * (e.g. redial, connection lost before terminal, peer fanout completing the
 * run the local collector never saw).
 *
 * The fix retires the turn at TWO points, both keyed by the turn id:
 *  - in `emitTurnFrame(TurnDone)` after a successful terminal emit, the entry
 *    is removed proactively so an `invokeOnCompletion` that never fires (stuck
 *    send job) cannot leave a phantom "Thinking…" presence;
 *  - in the observer path, when the projection of a stream_delta carries a
 *    `ServerFrame.TurnDone` for a conversation whose `activeTurns` still holds
 *    an entry, `retireActiveTurn` completes `terminalReached` and removes the
 *    entry — without re-emitting the terminal (the engine path owns the
 *    terminal emit slot) and without claiming the terminal guard (so an
 *    engine path that fires afterwards can still emit the frame exactly once).
 *
 * This test exercises the SECOND path: the engine never emits a TurnDone
 * (`client.input` is stuck on `awaitCancellation` AND the engine's
 * `client.events` subscriber is dead), but the observer sees the fanned-out
 * stop_reason. Before the fix the entry lingered in `activeTurns` and
 * `hasActiveChatTurn` stayed true forever; after the fix the entry is removed
 * on the next observer cycle.
 */
class IrohChannelTransportDir4kObserverTest {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /**
     * A [DualPathClient] variant whose engine-view ([AppServerClient.events]) is
     * a SEPARATE flow that the test never emits to. Only the observer-view
     * (`observerStream` below) ever sees the stop_reason. This emulates the
     * production race the bug-fix targets: the engine's collector is dead
     * (connection lost / cancelled) before the terminal arrived, so the engine
     * path never emits a TurnDone — the observer is the only place that can
     * see the terminal and retire the local turn.
     */
    private class SplitPathClient : AppServerClient {
        /** Engine's view: subscribed to by AppServerTurnEngine's collector. */
        private val engineStream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = engineStream

        val abortCommands = CopyOnWriteArrayList<AppServerCommand.AbortMessage>()

        @Volatile
        var inputReceived = false

        /** Engine-stream subscriber count (the engine's collector). */
        val engineSubscriberCount: Int get() = engineStream.subscriptionCount.value

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
            inputReceived = true
            // Stuck: the engine's channelFlow never proceeds past this point, so
            // the engine NEVER subscribes to engineStream (the collector's
            // `collectTurnWithIdleWatchdog` is launched inside the channelFlow
            // body, after `client.input(...)`). With `awaitCancellation` here
            // the engine view stays empty: even if we tried to emit a
            // stop_reason to engineStream nobody would see it.
            awaitCancellation()
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
            abortCommands += command
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
    }

    /**
     * Helper: builds a separate observer-only flow and wires it as the
     * [IrohConnectionHandle.observerStreamFrames] override. The transport's
     * passive-observer ingestion loop will subscribe to this flow, but the
     * engine's internal collector subscribes only to `client.events` (which
     * never gets an emission in this test). Returns the observer flow so the
     * test can drive stop_reasons.
     */
    private suspend fun connected(
        client: SplitPathClient,
        observerStream: MutableSharedFlow<AppServerReceivedFrame>,
    ): Pair<IrohChannelTransport, AppServerTurnEngine> {
        val engine = AppServerTurnEngine(client = client)
        val transport = IrohChannelTransport(
            scope = clientScope,
            activeConfigProvider = { IrohConnectConfig("iroh://ticket", "", "device", "test") },
            testDialer = { config ->
                IrohConnectionHandle(
                    config = config,
                    ticket = "ticket",
                    sessionId = "session",
                    turnEngine = engine,
                    observerStreamFrames = observerStream,
                    close = {},
                )
            },
            serverTerminalWaitMs = 200L,
        )
        transport.connect("iroh://ticket", "", "device", "test")
        withTimeout(10.seconds) {
            while (transport.state.value !is ChannelTransportState.Connected) delay(10.milliseconds)
        }
        // Wait for the observer collector to actually subscribe to the stream
        // (replay=0 — anything driven earlier is dropped on the floor).
        withTimeout(10.seconds) { while (observerStream.subscriptionCount.value < 1) delay(5.milliseconds) }
        return transport to engine
    }

    private suspend fun collectEvents(
        transport: IrohChannelTransport,
        frames: CopyOnWriteArrayList<ServerFrame>,
    ) {
        val job = clientScope.async(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { frames.add(it) }
        }
        // Leave the collector running for the duration of the test — `events`
        // has replay=0, so any frame emitted before the collector subscribed
        // would be silently dropped.
        job.invokeOnCompletion { /* keep reference alive until test cleanup */ }
    }

    private suspend fun streamDelta(
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
              "emitted_at": "2026-08-05T00:00:00Z",
              "idempotency_key": "dir4k-evt-$conversationId-$seq",
              "delta": $delta
            }
        """.trimIndent()
        return AppServerProtocol.decodeFrame(body, AppServerChannel.Stream)
    }

    private suspend fun emitStopReason(
        observerStream: MutableSharedFlow<AppServerReceivedFrame>,
        conversationId: String,
        seq: Long,
    ) {
        observerStream.emit(streamDelta(AGENT, conversationId, seq, """{"message_type": "stop_reason", "stop_reason": "end_turn"}"""))
    }

    // ------------------------------------------------------------------ (1)

    @Test
    fun observerPathTurnDoneRetiresTheInitiatorsLocalActiveTurn() = runBlocking {
        val client = SplitPathClient()
        val observerStream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val (transport, _) = connected(client, observerStream)
        val frames = CopyOnWriteArrayList<ServerFrame>()
        collectEvents(transport, frames)
        try {
            // 1. Start the local turn. The transport registers the ActiveTurn
            //    in `activeTurns` and emits TurnStarted. The engine is stuck on
            //    `client.input.awaitCancellation`, so it will never emit a
            //    TurnDone of its own.
            assertTrue(transport.send(AGENT, CONV_A, "hi A", "otid-a", null, false))
            withTimeout(10.seconds) {
                while (frames.none { it is ServerFrame.TurnStarted && it.conversationId == CONV_A }) {
                    delay(10.milliseconds)
                }
            }
            // The engine's collector IS launched inside the channelFlow body (it
            // subscribes to `client.events` immediately, before `client.input`
            // is awaited). But because `client.input` does
            // `awaitCancellation()` the engine never progresses past the input
            // call — it cannot deliver a terminal through the engine path. The
            // observer path is the only one that can reach the terminal,
// regardless of subscription count.
            delay(50.milliseconds)
            assertTrue(
                transport.hasActiveChatTurn(CONV_A),
                "sanity: the initiator's ActiveTurn is live before the observer sees a terminal",
            )

            // 2. Drive a stop_reason through the OBSERVER stream only. The
            //    engine sees nothing (its events flow is empty and not driven),
            //    so the ONLY path that can process this terminal is the
            //    observer — which is exactly the scenario the bug-fix targets.
            emitStopReason(observerStream, CONV_A, seq = 10)

            // 3. Within one observer cycle, the entry must be retired:
            //    `hasActiveChatTurn` flips to false and `terminalReached`
            //    completes.
            withTimeout(5.seconds) {
                while (transport.hasActiveChatTurn(CONV_A)) delay(20.milliseconds)
            }
            assertFalse(
                transport.hasActiveChatTurn(CONV_A),
                "letta-mobile-dir4k: observer-path TurnDone must retire the initiator's ActiveTurn",
            )
        } finally {
            transport.disconnect()
        }
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV_A = "conv-a"
    }
}
