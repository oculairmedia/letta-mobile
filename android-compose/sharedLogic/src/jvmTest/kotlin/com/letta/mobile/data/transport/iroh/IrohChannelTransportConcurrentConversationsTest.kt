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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-or40x — TWO CONCURRENT CONVERSATIONS THROUGH ONE TRANSPORT.
 *
 * Reported symptom: with conversation A streaming in the background,
 * conversation B rendered a thinking indicator for A's work, and sending into B
 * while A was in flight froze BOTH conversations — neither settled.
 *
 * Root cause: the transport kept ONE process-wide turn slot (`activeTurn`,
 * `activeSendJob`, `interruptedTurn`). Every per-turn invariant built on it was
 * therefore global:
 *  - presence (`hasActiveChatTurn`) answered for whatever turn held the slot,
 *    so it bled onto every open surface;
 *  - starting B evicted A from the only reference to A, which silently flipped
 *    A's still-streaming frames from ENGINE-owned to OBSERVER-owned (both paths
 *    read the same stream, so A's frames were then double-emitted);
 *  - `cancel(conversationId)` was keyed in name only — it read the global slot
 *    and cancelled whatever job was in it, i.e. the OTHER conversation's.
 *
 * These tests drive two conversations concurrently through one transport and
 * assert BEHAVIOR. They fail when the keying is reverted to a single slot.
 *
 * The harness mirrors production's frame topology: the SAME flow feeds the turn
 * engine (`AppServerClient.events`) and the passive observer collector
 * (`IrohConnectionHandle.observerStreamFrames`) — which is why an ownership flip
 * shows up as duplicate frames rather than as nothing at all.
 */
class IrohChannelTransportConcurrentConversationsTest {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // ---------------------------------------------------------------- harness

    private suspend fun connected(client: DualPathClient): Pair<IrohChannelTransport, AppServerTurnEngine> {
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
                    // Production topology: the observer collector reads the same
                    // stream channel the engine's runTurn collects.
                    observerStreamFrames = client.stream,
                    close = {},
                )
            },
            serverTerminalWaitMs = 200L,
        )
        transport.connect("iroh://ticket", "", "device", "test")
        withTimeout(10.seconds) {
            while (transport.state.value !is ChannelTransportState.Connected) delay(10.milliseconds)
        }
        // Deterministic: wait for the OBSERVER collector to actually subscribe to
        // the stream (replay=0 — anything driven earlier is dropped on the floor).
        withTimeout(10.seconds) { while (client.subscriberCount < 1) delay(5.milliseconds) }
        return transport to engine
    }

    /**
     * Collects [IrohChannelTransport.events] into [frames], returning only once the
     * collector is genuinely subscribed. `events` has replay=0, so a
     * subscribe-and-hope-for-the-best collector races every frame the test drives.
     */
    private suspend fun collectEvents(
        transport: IrohChannelTransport,
        frames: MutableList<ServerFrame>,
    ): Deferred<Unit> {
        val subscribed = CompletableDeferred<Unit>()
        val job = clientScope.async {
            transport.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { frames.add(it) }
        }
        withTimeout(10.seconds) { subscribed.await() }
        return job
    }

    /** Starts conversation A's turn and waits until it is genuinely streaming. */
    private suspend fun startStreamingTurnOnA(
        transport: IrohChannelTransport,
        engine: AppServerTurnEngine,
        client: DualPathClient,
        frames: List<ServerFrame>,
    ): String {
        assertTrue(transport.send(AGENT, CONV_A, "hi A", "otid-a", null, false))
        withTimeout(10.seconds) { while (!engine.isBusy) delay(10.milliseconds) }
        withTimeout(10.seconds) { while (!client.inputReceived) delay(10.milliseconds) }
        // TWO subscribers must be live before the test drives any frame: the
        // observer collector AND the engine's runTurn collector. Gating on ">0"
        // is satisfied by the observer alone and lets a driven frame slip past a
        // not-yet-subscribed engine (replay=0 → silently dropped).
        withTimeout(10.seconds) { while (client.subscriberCount < 2) delay(5.milliseconds) }
        withTimeout(10.seconds) {
            while (frames.none { it is ServerFrame.TurnStarted && it.conversationId == CONV_A }) delay(10.milliseconds)
        }
        return frames.filterIsInstance<ServerFrame.TurnStarted>().first { it.conversationId == CONV_A }.turnId
    }

    /**
     * Sends into conversation B while A streams. The engine serializes turns, so
     * B's turn resolves fast with a busy failure — but the transport-level turn
     * for B is created, registered and torn down, which is precisely the sequence
     * that used to evict A from the single global slot.
     */
    private suspend fun sendIntoBWhileAStreams(
        transport: IrohChannelTransport,
        frames: List<ServerFrame>,
    ) {
        assertTrue(transport.send(AGENT, CONV_B, "hi B", "otid-b", null, false))
        withTimeout(10.seconds) {
            while (frames.none { it is ServerFrame.Error && it.conversationId == CONV_B }) delay(10.milliseconds)
        }
        // Let B's turn finish tearing down (job completion callback).
        delay(200.milliseconds)
    }

    // ------------------------------------------------------------------ (1)

    @Test
    fun aStreamingConversationDoesNotReportAnActiveTurnForAnotherConversation() = runBlocking {
        val client = DualPathClient()
        val (transport, engine) = connected(client)
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = collectEvents(transport, frames)
        try {
            startStreamingTurnOnA(transport, engine, client, frames)

            assertTrue(transport.hasActiveChatTurn(CONV_A), "A owns a live turn")
            assertFalse(
                transport.hasActiveChatTurn(CONV_B),
                "presence must be scoped: B has no turn while only A streams",
            )

            sendIntoBWhileAStreams(transport, frames)

            assertTrue(
                transport.hasActiveChatTurn(CONV_A),
                "A's turn must survive B's turn: starting B must not evict A from the transport's turn state",
            )
            assertFalse(transport.hasActiveChatTurn(CONV_B), "B's turn already settled")
            assertTrue(transport.hasAnyActiveChatTurn, "the transport as a whole is still busy with A")
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    // ------------------------------------------------------------------ (2)

    @Test
    fun startingASecondConversationDoesNotPreventTheFirstFromReachingTerminalAndClearing() = runBlocking {
        val client = DualPathClient()
        val (transport, engine) = connected(client)
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = collectEvents(transport, frames)
        try {
            val turnIdA = startStreamingTurnOnA(transport, engine, client, frames)
            sendIntoBWhileAStreams(transport, frames)

            val terminalsBefore = frames.filterIsInstance<ServerFrame.TurnDone>().size

            // A's own server terminal, after B has come and gone.
            client.emitStopReason(CONV_A, seq = 20)
            withTimeout(10.seconds) {
                while (frames.none { it is ServerFrame.TurnDone && it.turnId == turnIdA }) delay(20.milliseconds)
            }
            delay(300.milliseconds) // let any duplicate terminal race in

            // A's stop_reason must produce EXACTLY ONE terminal. Once B has
            // evicted A from the transport's turn state, A's stop_reason is
            // consumed by BOTH the engine and the observer, so the conversation
            // settles twice — under a different turn id each time.
            val terminalsAfter = frames.filterIsInstance<ServerFrame.TurnDone>().drop(terminalsBefore)
            assertEquals(
                1,
                terminalsAfter.size,
                "A's terminal must be emitted once, by one owner; got ${terminalsAfter.map { it.turnId to it.status }}",
            )
            val terminalsForA = terminalsAfter.filter { it.turnId == turnIdA }
            assertEquals(1, terminalsForA.size, "the terminal must carry A's own turn id")
            assertEquals("completed", terminalsForA.single().status)
            assertFalse(transport.hasActiveChatTurn(CONV_A), "A's turn state must clear on its terminal")
            assertFalse(transport.hasAnyActiveChatTurn, "no turn is live once A settled")
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    // ------------------------------------------------------------------ (3)

    @Test
    fun framesStayEngineOwnedForTheWholeLifeOfATurnRegardlessOfOtherConversations() = runBlocking {
        val client = DualPathClient()
        val (transport, engine) = connected(client)
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = collectEvents(transport, frames)
        try {
            startStreamingTurnOnA(transport, engine, client, frames)
            sendIntoBWhileAStreams(transport, frames)

            // A is STILL streaming. Its frames must remain engine-owned: the
            // observer collector reads the very same flow, so an ownership flip
            // shows up as a duplicated assistant row.
            client.emitAssistant(CONV_A, id = "cm-a-1", content = "A reply", seq = 21)
            // A conversation with NO local turn stays observer-owned, proving the
            // guard is a per-conversation lookup and not a blanket suppression.
            client.emitAssistant(CONV_C, id = "cm-c-1", content = "C reply", seq = 22)

            withTimeout(10.seconds) {
                while (frames.none { it is ServerFrame.AssistantMessage && it.content == "C reply" }) delay(20.milliseconds)
            }
            delay(300.milliseconds) // let any erroneous second emit land

            val aRows = frames.filterIsInstance<ServerFrame.AssistantMessage>().filter { it.content == "A reply" }
            assertEquals(
                1,
                aRows.size,
                "A's frames must be owned by exactly one consumer for the whole life of A's turn; " +
                    "got ${aRows.size} copies (an ownership flip double-emits)",
            )
            assertTrue(
                frames.any { it is ServerFrame.AssistantMessage && it.content == "C reply" },
                "a conversation with no local turn is still observer-owned",
            )
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    // ------------------------------------------------------------------ (4)

    @Test
    fun cancellingOneConversationDoesNotCancelAnotherConversationsTurn() = runBlocking {
        val client = DualPathClient()
        val (transport, engine) = connected(client)
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = collectEvents(transport, frames)
        try {
            val turnIdA = startStreamingTurnOnA(transport, engine, client, frames)

            // The user cancels conversation B. B has no live turn of its own —
            // this must NOT reach into A's turn.
            assertTrue(transport.cancel(CONV_B))
            // Longer than serverTerminalWaitMs, so a mis-keyed cancel would have
            // already aborted + synthesized a cancelled terminal for A by now.
            delay(600.milliseconds)

            assertTrue(
                client.abortCommands.isEmpty(),
                "cancel(B) must never abort A's server run; aborts=${client.abortCommands.size}",
            )
            assertTrue(
                transport.hasActiveChatTurn(CONV_A),
                "A's turn must still be live after cancelling a DIFFERENT conversation",
            )
            assertTrue(
                frames.any { it is ServerFrame.TurnDone && it.status == "cancelled" },
                "cancel always yields a terminal so the cancelled surface can never hang",
            )
            assertTrue(
                frames.none { it is ServerFrame.TurnDone && it.turnId == turnIdA },
                "A's turn must not have been terminated by B's cancel",
            )

            // A is untouched and still settles on its own server terminal — the
            // reported "neither settled" is exactly this not happening.
            client.emitStopReason(CONV_A, seq = 30)
            withTimeout(10.seconds) {
                while (frames.none { it is ServerFrame.TurnDone && it.turnId == turnIdA }) delay(20.milliseconds)
            }
            val terminalA = frames.filterIsInstance<ServerFrame.TurnDone>().single { it.turnId == turnIdA }
            assertEquals("completed", terminalA.status, "A settles normally, not as a casualty of B's cancel")
            assertFalse(transport.hasActiveChatTurn(CONV_A))
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    // ------------------------------------------------------------------ fake

    /**
     * An App Server client whose ONE stream flow is consumed by both the turn
     * engine and the transport's observer collector — the production topology
     * (`client.events` = merge(control, stream); observer reads `streamFrames`).
     * `input` suspends forever so a turn stays streaming until the test drives a
     * terminal (or something cancels the job — which is what these tests watch
     * for).
     */
    private class DualPathClient : AppServerClient {
        val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = stream

        val abortCommands = CopyOnWriteArrayList<AppServerCommand.AbortMessage>()

        @Volatile
        var inputReceived = false

        /** Live subscribers on the shared stream: observer collector + engine runTurn. */
        val subscriberCount: Int get() = stream.subscriptionCount.value

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

        suspend fun emitAssistant(conversationId: String, id: String, content: String, seq: Long) =
            emit(conversationId, seq, """{"message_type": "assistant_message", "id": "$id", "content": "$content"}""")

        suspend fun emitStopReason(conversationId: String, seq: Long) =
            emit(conversationId, seq, """{"message_type": "stop_reason", "stop_reason": "end_turn"}""")

        private suspend fun emit(conversationId: String, seq: Long, delta: String) {
            val body = """
                {
                  "type": "stream_delta",
                  "runtime": {"agent_id": "$AGENT", "conversation_id": "$conversationId"},
                  "event_seq": $seq,
                  "emitted_at": "2026-07-30T00:00:00Z",
                  "idempotency_key": "evt-$conversationId-$seq",
                  "delta": $delta
                }
            """.trimIndent()
            stream.emit(AppServerProtocol.decodeFrame(body, AppServerChannel.Stream))
        }
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV_A = "conv-a"
        const val CONV_B = "conv-b"
        const val CONV_C = "conv-c"
    }
}
