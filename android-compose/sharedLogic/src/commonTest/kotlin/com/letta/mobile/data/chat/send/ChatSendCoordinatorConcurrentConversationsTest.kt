package com.letta.mobile.data.chat.send

import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-or40x PR2 — the COORDINATOR half of the concurrent-conversation
 * defect. PR1 keyed the transport's turn slots; a perfectly keyed transport was
 * still not enough, because `ChatSendCoordinator` kept ONE process-wide set of
 * `activeWs*` turn-identity fields plus ONE [TurnIdentityLifecycle]:
 *
 *  - a send into conversation B overwrote conversation A's otid / local
 *    conversation / turn / run identity, so A's own terminal was fenced off as
 *    "stale" and A never settled — the conversation that looked frozen on device;
 *  - `clearActiveTurnState()` took no key, so A's turn ending wiped B's in-flight
 *    send identity along with A's;
 *  - `healStaleVisualPresence` asked the unscoped "is ANY turn live?" question,
 *    so B's live turn suppressed A's self-heal forever.
 *
 * Every test below drives TWO conversations through one coordinator and asserts
 * observable settlement behavior, not internal fields.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendCoordinatorConcurrentConversationsTest {

    @Test
    fun drainedRuntimeAuditBatchInvokesSinkOnceInGlobalFifoOrder() = runTest {
        val timeline = RecordingTimelineWriter()
        val recorded = mutableListOf<Pair<String?, String>>()
        var sinkCalls = 0
        val workerJob = Job()
        val coordinator = coordinator(
            timeline = timeline,
            ui = RecordingUiSink(),
            transport = FakeChannelTransport(mutableListOf(), activeChatTurn = true),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + workerJob),
            recordRuntimeEvents = { events ->
                sinkCalls++
                recorded += events.map { scopedEvent ->
                    scopedEvent.conversationId to requireNotNull(
                        (scopedEvent.event as? WsTimelineEvent.MessageDelta)?.message?.id,
                    )
                }
            },
        ) { CONV_B }

        coordinator.handleEvent(messageDelta("audit-a", CONV_A))
        coordinator.handleEvent(messageDelta("audit-b", CONV_B))
        runCurrent()

        assertEquals(1, sinkCalls, "one drained coordinator batch must invoke the runtime sink once")
        assertEquals(
            listOf<Pair<String?, String>>(CONV_A to "audit-a", CONV_B to "audit-b"),
            recorded,
            "accepted runtime audit events must retain coordinator arrival order",
        )
        workerJob.cancel()
    }

    @Test
    fun blockedRuntimeAuditForConversationADoesNotBlockConversationBTimeline() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val aSinkEntered = CompletableDeferred<Unit>()
            val releaseASink = CompletableDeferred<Unit>()
            val persisted = mutableListOf<String>()
            val coordinator = coordinator(
                timeline = timeline,
                ui = RecordingUiSink(),
                transport = FakeChannelTransport(mutableListOf(), activeChatTurn = true),
                recordRuntimeEvent = { event, conversationId ->
                    val messageId = requireNotNull((event as? WsTimelineEvent.MessageDelta)?.message?.id)
                    if (conversationId == CONV_A) {
                        aSinkEntered.complete(Unit)
                        releaseASink.await()
                    }
                    persisted += messageId
                },
            ) { CONV_B }

            val aEvent = messageDelta("blocked-a", CONV_A)
            val bEvent = messageDelta("progress-b-1", CONV_B)
            val queuedBEvent = messageDelta("progress-b-2", CONV_B)
            val aHandling = launch { coordinator.handleEvent(aEvent) }
            aSinkEntered.await()
            val bHandling = launch { coordinator.handleEvent(bEvent) }
            val queuedBHandling = launch { coordinator.handleEvent(queuedBEvent) }

            try {
                assertTrue(
                    timeline.ingestedMessages.map { it.id }.containsAll(listOf("progress-b-1", "progress-b-2")),
                    "B timeline must progress while A runtime persistence remains blocked",
                )
            } finally {
                releaseASink.complete(Unit)
            }
            aHandling.join()
            bHandling.join()
            queuedBHandling.join()
            advanceUntilIdle()

            assertEquals(listOf("blocked-a", "progress-b-1", "progress-b-2"), persisted)
        }

    @Test
    fun runtimeAuditSinkFailureDoesNotStopLaterQueuedRecords() = runTest(UnconfinedTestDispatcher()) {
        val persisted = mutableListOf<String>()
        val coordinator = coordinator(
            timeline = RecordingTimelineWriter(),
            ui = RecordingUiSink(),
            transport = FakeChannelTransport(mutableListOf(), activeChatTurn = true),
            recordRuntimeEvent = { event, _ ->
                val messageId = requireNotNull((event as? WsTimelineEvent.MessageDelta)?.message?.id)
                if (messageId == "sink-failure") error("expected sink failure")
                persisted += messageId
            },
        ) { CONV_B }

        coordinator.handleEvent(messageDelta("sink-failure", CONV_A))
        coordinator.handleEvent(messageDelta("after-failure", CONV_B))
        advanceUntilIdle()

        assertEquals(listOf("after-failure"), persisted)
    }

    @Test
    fun cancellingCoordinatorScopeStopsRuntimeAuditBacklog() = runTest(UnconfinedTestDispatcher()) {
        val workerJob = Job()
        val workerScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + workerJob)
        val sinkEntered = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        val coordinator = coordinator(
            timeline = RecordingTimelineWriter(),
            ui = RecordingUiSink(),
            transport = FakeChannelTransport(mutableListOf(), activeChatTurn = true),
            scope = workerScope,
            recordRuntimeEvent = sink@ { event, _ ->
                val messageId = requireNotNull((event as? WsTimelineEvent.MessageDelta)?.message?.id)
                if (messageId == "cancel-worker") {
                    sinkEntered.complete(Unit)
                    runCatching { releaseSink.await() }
                    return@sink
                }
                persisted += messageId
            },
        ) { CONV_B }

        coordinator.handleEvent(messageDelta("cancel-worker", CONV_A))
        sinkEntered.await()
        coordinator.handleEvent(messageDelta("must-not-persist", CONV_B))
        workerJob.cancel()
        releaseSink.complete(Unit)
        advanceUntilIdle()

        assertTrue(persisted.isEmpty(), "scope cancellation must stop queued persistence work")
    }

    /**
     * Regression 1: B's send while A's turn is in flight must not evict A's turn
     * identity — A's own terminal still settles A.
     *
     * Against the unkeyed coordinator, B's `acceptSend` replaced the single
     * identity, so `TurnDone(turn-a)` was rejected by the terminal fence and
     * "conv-a" never reached `clearExternalTransportActive` at all.
     */
    @Test
    fun sendIntoSecondConversationDoesNotEvictFirstConversationTurnIdentity() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink()
            val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
            var active: String? = CONV_A
            val coordinator = coordinator(timeline, ui, transport) { active }

            coordinator.send("hello A").join()
            val otidA = timeline.externalLocals.single { it.conversationId == CONV_A }.otid
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))

            // The user switches to B and sends while A is still streaming.
            active = CONV_B
            coordinator.send("hello B").join()
            val otidB = timeline.externalLocals.single { it.conversationId == CONV_B }.otid
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-b", AGENT_ID, CONV_B, "run-b"))

            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-a", "run-a", BridgeTurnStatus.Completed))
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-b", "run-b", BridgeTurnStatus.Completed))
            advanceUntilIdle()

            // Both conversations settle, each under its OWN terminal and in order.
            assertEquals(listOf(CONV_A, CONV_B), timeline.clearedActiveConversations)
            assertTrue(
                timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_A, otidA)),
                "A's optimistic-local must settle against A",
            )
            assertTrue(
                timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_B, otidB)),
                "B's optimistic-local must settle against B",
            )
            // No cross-attribution: neither otid may be settled into the other conversation.
            assertTrue(timeline.sentLocals.none { it.conversationId == CONV_A && it.otid == otidB })
            assertTrue(timeline.sentLocals.none { it.conversationId == CONV_B && it.otid == otidA })
        }

    /**
     * Regression 2: A's turn ending must not wipe B's in-flight send identity.
     *
     * B's send is accepted while A is still awaiting its `TurnStarted`. Against
     * the unkeyed coordinator, A's `TurnStarted` adopted B's otid as its own
     * "stale otid" and A's terminal then settled B's optimistic-local row and
     * cleared it — B's own terminal arrived to find nothing left to settle.
     */
    @Test
    fun firstConversationTerminalDoesNotWipeSecondConversationInFlightSend() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink()
            val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
            var active: String? = CONV_A
            val coordinator = coordinator(timeline, ui, transport) { active }

            coordinator.send("hello A").join()
            val otidA = timeline.externalLocals.single { it.conversationId == CONV_A }.otid
            active = CONV_B
            coordinator.send("hello B").join()
            val otidB = timeline.externalLocals.single { it.conversationId == CONV_B }.otid

            // A's turn starts and ends while B's send is still awaiting its TurnStarted.
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-a", "run-a", BridgeTurnStatus.Completed))
            advanceUntilIdle()

            assertEquals(listOf(CONV_A), timeline.clearedActiveConversations)
            assertTrue(
                timeline.sentLocals.none { it.otid == otidB },
                "A's terminal must not settle B's still in-flight send",
            )
            assertTrue(timeline.failedLocals.none { it.otid == otidB })

            // B's identity survived, so B's own terminal still settles B.
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-b", AGENT_ID, CONV_B, "run-b"))
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-b", "run-b", BridgeTurnStatus.Completed))
            advanceUntilIdle()

            assertEquals(listOf(CONV_A, CONV_B), timeline.clearedActiveConversations)
            assertTrue(timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_B, otidB)))
            assertTrue(timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_A, otidA)))
        }

    /**
     * Regression 3: a stale visual presence on A must self-heal even while B
     * holds a live turn. The old unscoped `hasAnyActiveChatTurn` check let B's
     * turn suppress A's heal, so an orphaned "thinking" indicator on A never
     * cleared and the next send queued behind a ghost turn.
     */
    @Test
    fun stalePresenceHealsForOwnConversationWhileAnotherConversationHasLiveTurn() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink(isStreaming = true, isAgentTyping = true)
            val transport = FakeChannelTransport(
                mutableListOf(true),
                activeChatTurn = false,
                activeChatTurnConversations = mutableSetOf(CONV_B),
            )
            val coordinator = coordinator(timeline, ui, transport) { CONV_A }

            coordinator.send("hello A").join()
            advanceUntilIdle()

            assertEquals(1, ui.visualCompletions, "A's stale presence must heal despite B's live turn")
            assertEquals(listOf("hello A"), transport.sentTexts)
            assertEquals(listOf(CONV_A), transport.sentConversationIds)
        }

    /** A send into the conversation that genuinely owns the live turn still must NOT heal. */
    @Test
    fun stalePresenceDoesNotHealWhileOwnConversationHasLiveTurn() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink(isStreaming = true, isAgentTyping = true)
            val transport = FakeChannelTransport(
                mutableListOf(true),
                activeChatTurn = false,
                activeChatTurnConversations = mutableSetOf(CONV_A),
            )
            val coordinator = coordinator(timeline, ui, transport) { CONV_A }

            coordinator.send("hello A").join()
            advanceUntilIdle()

            assertEquals(0, ui.visualCompletions)
        }

    // ---------------------------------------------------------------------
    // PR2 review (Codex on #1056) regressions.
    // ---------------------------------------------------------------------

    /**
     * Finding 1: [ChatSendUiSink] is a singleton bound to the VISIBLE
     * conversation. Keyed turn state alone still let conversation A's terminal
     * clear B's thinking indicator and paint A's error over B.
     */
    @Test
    fun backgroundConversationTerminalDoesNotClearVisibleConversationPresence() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink()
            val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
            var active: String? = CONV_A
            val coordinator = coordinator(timeline, ui, transport) { active }

            coordinator.send("hello A").join()
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))

            // The user is now looking at B, which has its own live turn.
            active = CONV_B
            coordinator.send("hello B").join()
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-b", AGENT_ID, CONV_B, "run-b"))

            // A fails in the background.
            coordinator.handleEvent(
                WsTimelineEvent.Error(
                    code = "provider_error",
                    message = "A blew up",
                    conversationId = CONV_A,
                    turnId = "turn-a",
                    runId = "run-a",
                ),
            )
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-a", "run-a", BridgeTurnStatus.Failed))
            advanceUntilIdle()

            // B is visible and still streaming; A's failure never touched its UI.
            assertTrue(ui.isStreaming(), "B's presence must survive A's terminal")
            assertTrue(ui.isAgentTyping())
            assertNull(ui.currentError(), "A's error must not be painted over B")
            assertTrue(ui.turnsFinished.isEmpty())
            // A still settled its OWN timeline rows.
            assertTrue(timeline.clearedActiveConversations.contains(CONV_A))
        }

    /**
     * Finding 2: once A's state was cleared (here by SubscribeDone), a delayed
     * TurnDone for A matched nothing and was handed to B — the sole send awaiting
     * its TurnStarted — whose independent lifecycle had never fenced A's turn id
     * and therefore accepted the terminal, settling B prematurely.
     */
    @Test
    fun delayedTerminalForClearedConversationDoesNotSettleAwaitingSend() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink()
            val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
            var active: String? = CONV_A
            val coordinator = coordinator(timeline, ui, transport) { active }

            coordinator.send("hello A").join()
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))
            // A is settled and CLEARED by a subscribe terminal, not by TurnDone.
            coordinator.handleEvent(WsTimelineEvent.SubscribeDone("run-a", lastSeq = 1L, status = "completed"))
            advanceUntilIdle()
            assertEquals(listOf(CONV_A), timeline.clearedActiveConversations)

            // B's send is now the only one awaiting a TurnStarted.
            active = CONV_B
            coordinator.send("hello B").join()
            val otidB = timeline.externalLocals.single { it.conversationId == CONV_B }.otid

            // A's delayed terminal arrives. It belongs to A's history, not to B.
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-a", "run-a", BridgeTurnStatus.Completed))
            advanceUntilIdle()

            assertTrue(timeline.sentLocals.none { it.otid == otidB }, "B must not be settled by A's terminal")
            assertTrue(timeline.failedLocals.none { it.otid == otidB })
            assertEquals(listOf(CONV_A), timeline.clearedActiveConversations)

            // B's own terminal still settles B.
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-b", AGENT_ID, CONV_B, "run-b"))
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-b", "run-b", BridgeTurnStatus.Completed))
            advanceUntilIdle()
            assertTrue(timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_B, otidB)))
        }

    /**
     * Finding 3: the entry cap must never cost a LIVE turn its state. A live turn
     * in an older conversation used to be evicted by insertion order as soon as a
     * 33rd conversation appeared, even with dozens of settled entries available.
     */
    @Test
    fun liveTurnStateSurvivesManyOtherConversations() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        val coordinator = coordinator(timeline, ui, transport) { CONV_A }

        coordinator.send("hello A").join()
        val otidA = timeline.externalLocals.single { it.conversationId == CONV_A }.otid
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))

        // 40 other conversations come and go, each fully settled.
        repeat(OVERFLOW_CONVERSATIONS) { index ->
            val conversationId = "conv-x$index"
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-x$index", AGENT_ID, conversationId, "run-x$index"))
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-x$index", "run-x$index", BridgeTurnStatus.Completed))
        }
        advanceUntilIdle()

        // A's live turn is still tracked, so A's own terminal still settles A.
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-a", "run-a", BridgeTurnStatus.Completed))
        advanceUntilIdle()

        assertTrue(
            timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_A, otidA)),
            "A's live turn state must survive the entry cap",
        )
        assertTrue(timeline.clearedActiveConversations.contains(CONV_A))
    }

    /**
     * Finding 4: `wsChatBridge.events` is global and Error frames carry no agent
     * id, so an unconditional `stateFor` opened another agent's conversation
     * inside this coordinator and recorded that failure through this agent's sink.
     */
    @Test
    fun errorForUnownedConversationIsDroppedNotAdopted() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        val recorded = mutableListOf<Pair<WsTimelineEvent, String?>>()
        val coordinator = coordinator(
            timeline,
            ui,
            transport,
            recordRuntimeEvent = { event, conversationId -> recorded += event to conversationId },
        ) { CONV_A }

        coordinator.send("hello A").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))

        // A failure for a conversation this coordinator has never seen and does
        // not have on screen — it belongs to another agent's coordinator.
        coordinator.handleEvent(
            WsTimelineEvent.Error(
                code = "provider_error",
                message = "foreign agent failure",
                conversationId = "conv-owned-by-another-agent",
                turnId = "turn-foreign",
                runId = "run-foreign",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            recorded.none { it.first is WsTimelineEvent.Error },
            "a foreign conversation's error must not be recorded through this agent's sink",
        )

        // ...and it must not have poisoned A's turn: A completes cleanly.
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-a", "run-a", BridgeTurnStatus.Completed))
        advanceUntilIdle()
        assertNull(ui.currentError())
        assertEquals(listOf<String?>(null), ui.turnsFinished)
    }

    /**
     * Finding 5: the heal discarded a non-null otid without settling its
     * optimistic-local row or clearing the transport-active marker, so the old row
     * stayed SENDING forever — recreating the very stuck indicator it removes.
     */
    @Test
    fun stalePresenceHealSettlesOrphanedOptimisticRow() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        // No live turn anywhere: the first send's terminal simply never arrives.
        val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = false)
        val coordinator = coordinator(timeline, ui, transport) { CONV_A }

        coordinator.send("orphaned").join()
        val orphanedOtid = timeline.externalLocals.single().otid
        assertTrue(timeline.sentLocals.isEmpty())

        // The next send heals the stale presence — and must settle what it drops.
        coordinator.send("next").join()
        advanceUntilIdle()

        assertTrue(
            timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_A, orphanedOtid)),
            "the orphaned optimistic-local row must be settled, not silently dropped",
        )
        assertTrue(
            timeline.clearedActiveConversations.contains(CONV_A),
            "the external-transport-active marker must be cleared by the heal",
        )
    }

    /**
     * letta-mobile-ce2xr: When a send on [CONV_A] produces an inbound turn on a different
     * server-assigned conversation id ([CONV_SERVER]), the user message echo carrying [otid]
     * aliases the server conversation to [CONV_A] and binds the turn so deltas and completions
     * resolve to [CONV_A] without suppressing the active foreground UI.
     */
    @Test
    fun inboundTurnBoundToOriginatingSendByOtidWhenUserMessageArrivesFirst() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        val coordinator = coordinator(timeline, ui, transport) { CONV_A }

        coordinator.send("hello from user", targetConversationId = CONV_A).join()
        val sentOtid = timeline.externalLocals.single().otid
        assertEquals(CONV_A, timeline.externalLocals.single().conversationId)

        // 1. Inbound UserMessage echo arrives on a server-assigned conversation id bearing the otid
        val userEcho = WsTimelineEvent.MessageDelta(
            message = LettaMessage(
                id = "msg-user-echo",
                role = "user",
                messageType = "user_message",
                content = "hello from user",
                date = "2026-09-10T16:00:00Z",
                otid = sentOtid,
                turnId = "turn-server-1",
                runId = "run-server-1",
            ),
            conversationId = CONV_SERVER,
            turnId = "turn-server-1",
        )
        coordinator.handleEvent(userEcho)

        // 2. TurnStarted arrives on CONV_SERVER
        coordinator.handleEvent(
            WsTimelineEvent.TurnStarted(
                agentId = AGENT_ID,
                conversationId = CONV_SERVER,
                turnId = "turn-server-1",
                runId = "run-server-1",
            ),
        )

        // 3. Assistant reply arrives on CONV_SERVER
        val assistantDelta = WsTimelineEvent.MessageDelta(
            message = AssistantMessage(
                id = "msg-assistant-1",
                contentRaw = JsonPrimitive("assistant reply"),
                turnId = "turn-server-1",
                runId = "run-server-1",
            ),
            conversationId = CONV_SERVER,
            turnId = "turn-server-1",
        )
        coordinator.handleEvent(assistantDelta)

        // 4. TurnDone arrives
        coordinator.handleEvent(
            WsTimelineEvent.TurnDone(
                turnId = "turn-server-1",
                runId = "run-server-1",
                status = BridgeTurnStatus.Completed,
            ),
        )
        advanceUntilIdle()

        // Deltas should have ingested under CONV_A, not orphaned under CONV_SERVER
        assertTrue(
            timeline.ingestedMessages.any { it.id == "msg-assistant-1" },
            "assistant message must be ingested into timeline",
        )
        assertEquals(1, ui.turnsFinished.size, "exactly one turn completion must fire")
        assertNull(ui.currentError())
        assertTrue(
            timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_A, sentOtid)),
            "originating local send on CONV_A must be marked sent",
        )
    }

    /**
     * letta-mobile-ce2xr (Trap 2): TurnStarted on [CONV_SERVER] arrives before the UserMessage
     * echo carrying the [otid]. When the UserMessage echo subsequently arrives, it re-keys and merges
     * the orphan server state into the originating send's state on [CONV_A].
     */
    @Test
    fun inboundTurnBoundAndRekeyedWhenTurnStartedArrivesBeforeUserMessage() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
        val coordinator = coordinator(timeline, ui, transport) { CONV_A }

        // Start send A on CONV_A
        coordinator.send("hello from user", targetConversationId = CONV_A).join()
        val sentOtid = timeline.externalLocals.single().otid

        // 1. TurnStarted arrives on CONV_SERVER first (before UserMessage echo)
        coordinator.handleEvent(
            WsTimelineEvent.TurnStarted(
                agentId = AGENT_ID,
                conversationId = CONV_SERVER,
                turnId = "turn-server-2",
                runId = "run-server-2",
            ),
        )

        // 2. UserMessage echo arrives on CONV_SERVER with the matching otid
        val userEcho = WsTimelineEvent.MessageDelta(
            message = LettaMessage(
                id = "msg-user-echo-2",
                role = "user",
                messageType = "user_message",
                content = "hello from user",
                date = "2026-09-10T16:00:00Z",
                otid = sentOtid,
                turnId = "turn-server-2",
                runId = "run-server-2",
            ),
            conversationId = CONV_SERVER,
            turnId = "turn-server-2",
        )
        coordinator.handleEvent(userEcho)

        // 3. Assistant reply arrives on CONV_SERVER
        coordinator.handleEvent(
            WsTimelineEvent.MessageDelta(
                message = AssistantMessage(
                    id = "msg-assistant-2",
                    contentRaw = JsonPrimitive("assistant reply 2"),
                    turnId = "turn-server-2",
                    runId = "run-server-2",
                ),
                conversationId = CONV_SERVER,
                turnId = "turn-server-2",
            ),
        )

        // 4. TurnDone arrives
        coordinator.handleEvent(
            WsTimelineEvent.TurnDone(
                turnId = "turn-server-2",
                runId = "run-server-2",
                status = BridgeTurnStatus.Completed,
            ),
        )
        advanceUntilIdle()

        assertTrue(
            timeline.ingestedMessages.any { it.id == "msg-assistant-2" },
            "assistant message must be ingested",
        )
        assertEquals(1, ui.turnsFinished.size, "exactly one turn completion must fire")
        assertTrue(
            timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker(CONV_A, sentOtid)),
            "originating send on CONV_A must be settled as sent",
        )
    }

    /**
     * letta-mobile-ce2xr (Trap 1): Replay and reconnect can emit an echoed [otid] for an
     * already-settled turn. The coordinator's [settledOtids] fence must ensure binding is idempotent
     * and does not resurrect streaming state or latch presence.
     */
    @Test
    fun replayedOtidForSettledTurnIsIdempotentAndDoesNotResurrectState() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        val coordinator = coordinator(timeline, ui, transport) { CONV_A }

        coordinator.send("initial send", targetConversationId = CONV_A).join()
        val sentOtid = timeline.externalLocals.single().otid

        coordinator.handleEvent(
            WsTimelineEvent.TurnStarted(
                agentId = AGENT_ID,
                conversationId = CONV_A,
                turnId = "turn-1",
                runId = "run-1",
            ),
        )
        coordinator.handleEvent(
            WsTimelineEvent.TurnDone(
                turnId = "turn-1",
                runId = "run-1",
                status = BridgeTurnStatus.Completed,
            ),
        )
        advanceUntilIdle()

        // The turn is now settled
        assertEquals(1, ui.turnsFinished.size)
        assertTrue(!ui.isStreaming(), "streaming must be false after turn finish")

        // Inbound replay frame arrives with the same otid
        val replayFrame = WsTimelineEvent.MessageDelta(
            message = LettaMessage(
                id = "msg-replay",
                role = "user",
                messageType = "user_message",
                content = "initial send",
                date = "2026-09-10T16:00:00Z",
                otid = sentOtid,
                turnId = "turn-1",
                runId = "run-1",
            ),
            isReplay = true,
            conversationId = CONV_SERVER,
            turnId = "turn-1",
        )
        coordinator.handleEvent(replayFrame)
        advanceUntilIdle()

        assertTrue(!ui.isStreaming(), "replayed frame must not resurrect streaming state")
        assertEquals(1, ui.turnsFinished.size, "no duplicate turn completion")
    }

    /**
     * letta-mobile-ce2xr: A remotely-started turn on an unseen server conversation ([CONV_SERVER])
     * arriving while a send is in flight on [CONV_A] must NOT be heuristic-aliased or captured by
     * [CONV_A] without otid evidence.
     */
    @Test
    fun remotelyStartedTurnOnUnseenConversationIsNotCapturedByAwaitingSend() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        val coordinator = coordinator(timeline, ui, transport) { CONV_A }

        // Start send on CONV_A (awaiting TurnStarted)
        coordinator.send("send in flight", targetConversationId = CONV_A).join()
        val sentOtid = timeline.externalLocals.single().otid

        // Remotely started turn arrives on CONV_SERVER without matching otid
        coordinator.handleEvent(
            WsTimelineEvent.TurnStarted(
                agentId = AGENT_ID,
                conversationId = CONV_SERVER,
                turnId = "turn-remote-1",
                runId = "run-remote-1",
            ),
        )

        val remoteAssistant = WsTimelineEvent.MessageDelta(
            message = AssistantMessage(
                id = "msg-remote-assistant",
                contentRaw = JsonPrimitive("remote reply"),
                turnId = "turn-remote-1",
                runId = "run-remote-1",
            ),
            conversationId = CONV_SERVER,
            turnId = "turn-remote-1",
        )
        coordinator.handleEvent(remoteAssistant)

        // Deltas for remote turn must ingest under CONV_SERVER, NOT under CONV_A
        assertTrue(
            timeline.ingestedMessages.any { it.id == "msg-remote-assistant" },
            "remote assistant message must be ingested",
        )
        // CONV_A's send must still be awaiting its own turn
        assertTrue(timeline.sentLocals.none { it.otid == sentOtid }, "CONV_A's send must not be prematurely settled by remote turn")
        assertEquals(0, ui.turnsFinished.size, "remote turn must not finish CONV_A's turn")
    }

    private fun coordinator(
        timeline: RecordingTimelineWriter,
        ui: RecordingUiSink,
        transport: FakeChannelTransport,
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
        recordRuntimeEvent: suspend (WsTimelineEvent, String?) -> Unit = { _, _ -> },
        recordRuntimeEvents: suspend (List<ScopedRuntimeEvent>) -> Unit = { events ->
            events.forEach { event -> recordRuntimeEvent(event.event, event.conversationId) }
        },
        activeConversationId: () -> String?,
    ) = ChatSendCoordinator(
        scope = scope,
        agentId = AGENT_ID,
        activeConfig = { LettaConfig("shim", LettaConfig.Mode.SELF_HOSTED, "http://localhost:8291", "token") },
        wsChatBridge = WsChatBridge(transport),
        timelineRepository = timeline,
        conversationRepository = FakeConversationRepository(),
        ui = ui,
        clearComposerAfterSend = {},
        activeConversationId = activeConversationId,
        setActiveConversationId = {},
        startTimelineObserver = {},
        clientVersion = { "test" },
        otidGenerator = { "otid-${++otid}" },
        recordRuntimeEvents = recordRuntimeEvents,
    )

    private class RecordingUiSink(
        private var error: String? = null,
        private var isStreaming: Boolean = false,
        private var isAgentTyping: Boolean = false,
    ) : ChatSendUiSink {
        var visualCompletions = 0
        val turnsFinished = mutableListOf<String?>()
        override fun currentError(): String? = error
        override fun isStreaming(): Boolean = isStreaming
        override fun isAgentTyping(): Boolean = isAgentTyping
        override fun onSendDispatched(conversationId: String?) { error = null; isStreaming = true; isAgentTyping = true }
        override fun onSendQueued(conversationId: String) { error = null; isStreaming = true; isAgentTyping = true }
        override fun onSendFailed(message: String) { error = message; isStreaming = false; isAgentTyping = false }
        override fun onError(message: String?) { error = message }
        override fun onTurnStarted(conversationId: String) { error = null; isStreaming = true; isAgentTyping = true }
        override fun onMessageDelta(conversationId: String) { error = null; isStreaming = true; isAgentTyping = true }
        override fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int) = Unit
        override fun onTurnFinished(error: String?) { turnsFinished += error; this.error = error; isStreaming = false; isAgentTyping = false }
        override fun onTurnVisuallyComplete() { visualCompletions++; isStreaming = false; isAgentTyping = false }
        override fun onTransientDisconnect(hasActiveSend: Boolean) { error = null; isStreaming = hasActiveSend; isAgentTyping = hasActiveSend }
        override fun onDisconnectFailure(error: String) { this.error = error; isStreaming = false; isAgentTyping = false }
    }

    private class RecordingTimelineWriter : TimelineExternalTransportWriter {
        val externalLocals = mutableListOf<ExternalLocal>()
        val ingestedMessages = mutableListOf<LettaMessage>()
        val sentLocals = mutableListOf<LocalMarker>()
        val failedLocals = mutableListOf<LocalMarker>()
        val clearedActiveConversations = mutableListOf<String>()
        override suspend fun appendExternalTransportLocal(conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String { externalLocals += ExternalLocal(conversationId, content, otid); return otid }
        override suspend fun appendExternalTransportLocal(agentId: String?, conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String = appendExternalTransportLocal(conversationId, content, otid, attachments)
        override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage, source: String) { ingestedMessages += message }
        override suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage, source: String) { ingestedMessages += message }
        override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) { sentLocals += LocalMarker(conversationId, otid) }
        override suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) { sentLocals += LocalMarker(conversationId, otid) }
        override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) { failedLocals += LocalMarker(conversationId, otid) }
        override suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) { failedLocals += LocalMarker(conversationId, otid) }
        override suspend fun reconcileExternalTransportSend(conversationId: String, agentId: String, externalConversationId: String, otid: String) = Unit
        override suspend fun reconcileExternalTransportSendScoped(agentId: String?, conversationId: String, externalConversationId: String, otid: String) = Unit
        override suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?) = Unit
        override suspend fun repairExpiredConversationCursorScoped(agentId: String?, conversationId: String, fallbackSeq: Long?) = Unit
        override suspend fun clearExternalTransportActive(conversationId: String) { clearedActiveConversations += conversationId }
        override suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) { clearedActiveConversations += conversationId }
        override suspend fun cleanupAbandonedAssistantFragments(agentId: String?, conversationId: String, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String>): Int = 0
        override suspend fun reconcileRecentMessages(agentId: String?, conversationId: String, reason: String, forceRefresh: Boolean, connectionGeneration: Long): RecentMessagesReconcileOutcome = RecentMessagesReconcileOutcome.Applied(0)
        data class ExternalLocal(val conversationId: String, val content: String, val otid: String)
        data class LocalMarker(val conversationId: String, val otid: String)
    }

    private class FakeChannelTransport(
        val sendResults: MutableList<Boolean>,
        var activeChatTurn: Boolean = false,
        val activeChatTurnConversations: MutableSet<String> = mutableSetOf(),
    ) : IChannelTransport {
        override val state: StateFlow<ChannelTransportState> = MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val events = MutableSharedFlow<ServerFrame>()
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()
        override fun hasActiveChatTurn(conversationId: String): Boolean =
            activeChatTurn || conversationId in activeChatTurnConversations
        override val hasAnyActiveChatTurn: Boolean
            get() = activeChatTurn || activeChatTurnConversations.isNotEmpty()
        val sentTexts = mutableListOf<String>()
        val sentConversationIds = mutableListOf<String>()
        override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = Unit
        override fun send(agentId: String, conversationId: String, text: String, otid: String?, contentParts: JsonArray?, startNewConversation: Boolean): Boolean { sentTexts += text; sentConversationIds += conversationId; return sendResults.removeFirstOrNull() ?: true }
        override fun cancel(conversationId: String): Boolean = true
        override fun bye(): Boolean = true
        override suspend fun disconnect() = Unit
        override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = A2uiActionDispatchResult.Sent("frame-1")
        override fun subscribe(runId: String, cursor: Long): Boolean = true
        override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long) = error("unused")
        override suspend fun sendCronAdd(agentId: String, name: String, description: String, prompt: String, recurring: Boolean, cron: String?, every: String?, at: String?, timezone: String?, conversationId: String?, timeoutMs: Long) = error("unused")
        override suspend fun sendCronGet(taskId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendCronDelete(taskId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long) = error("unused")
        override suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long) = error("unused")
    }

    private class FakeConversationRepository : IConversationRepository {
        override fun getConversations(agentId: AgentId): Flow<List<Conversation>> = emptyFlow()
        override fun getCachedConversations(agentId: AgentId): List<Conversation> = emptyList()
        override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean = true
        override suspend fun refreshConversations(agentId: AgentId) = Unit
        override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean = false
        override suspend fun getConversation(id: ConversationId): Conversation = conversation(id.value, AGENT_ID)
        override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation = conversation("conv-created", agentId.value)
        override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) = Unit
        override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) = Unit
        override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) = Unit
        override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) = Unit
        override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String = "run"
        override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation = conversation("fork", agentId.value)
        private fun conversation(id: String, agentId: String) = Conversation(ConversationId(id), AgentId(agentId), "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z")
    }

    private fun messageDelta(messageId: String, conversationId: String) =
        WsTimelineEvent.MessageDelta(
            message = AssistantMessage(
                id = messageId,
                contentRaw = JsonPrimitive(messageId),
                runId = "run-$conversationId",
            ),
            conversationId = conversationId,
        )

    private companion object {
        const val AGENT_ID = "agent-1"
        const val CONV_A = "conv-a"
        const val CONV_B = "conv-b"
        const val CONV_SERVER = "conv-server-assigned"
        const val OVERFLOW_CONVERSATIONS = 40
        var otid = 0
    }
}
