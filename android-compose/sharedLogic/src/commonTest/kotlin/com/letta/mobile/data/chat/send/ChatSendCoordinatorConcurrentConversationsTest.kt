package com.letta.mobile.data.chat.send

import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.transport.WsTimelineEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
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
internal class ChatSendCoordinatorConcurrentConversationsTest : ChatSendCoordinatorConversationFixture() {

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

        coordinator.send("hello from user").join()
        val sentOtid = timeline.externalLocals.single().otid
        assertEquals(CONV_A, timeline.externalLocals.single().conversationId)

        // 1. Inbound UserMessage echo arrives on a server-assigned conversation id bearing the otid
        val userEcho = WsTimelineEvent.MessageDelta(
            message = UserMessage(
                id = "msg-user-echo",
                contentRaw = JsonPrimitive("hello from user"),
                date = "2026-09-10T16:00:00Z",
                otid = sentOtid,
                runId = "run-server-1",
            ),
            conversationId = CONV_SERVER,
            turnId = "turn-server-1",
        )
        coordinator.handleEvent(userEcho)

        // 2. TurnStarted arrives on CONV_SERVER
        coordinator.handleEvent(
            WsTimelineEvent.TurnStarted(
                turnId = "turn-server-1",
                agentId = AGENT_ID,
                conversationId = CONV_SERVER,
                runId = "run-server-1",
            ),
        )

        // 3. Assistant reply arrives on CONV_SERVER
        val assistantDelta = WsTimelineEvent.MessageDelta(
            message = AssistantMessage(
                id = "msg-assistant-1",
                contentRaw = JsonPrimitive("assistant reply"),
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
        coordinator.send("hello from user").join()
        val sentOtid = timeline.externalLocals.single().otid

        // 1. TurnStarted arrives on CONV_SERVER first (before UserMessage echo)
        coordinator.handleEvent(
            WsTimelineEvent.TurnStarted(
                turnId = "turn-server-2",
                agentId = AGENT_ID,
                conversationId = CONV_SERVER,
                runId = "run-server-2",
            ),
        )

        // 2. UserMessage echo arrives on CONV_SERVER with the matching otid
        val userEcho = WsTimelineEvent.MessageDelta(
            message = UserMessage(
                id = "msg-user-echo-2",
                contentRaw = JsonPrimitive("hello from user"),
                date = "2026-09-10T16:00:00Z",
                otid = sentOtid,
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

        coordinator.send("initial send").join()
        val sentOtid = timeline.externalLocals.single().otid

        coordinator.handleEvent(
            WsTimelineEvent.TurnStarted(
                turnId = "turn-1",
                agentId = AGENT_ID,
                conversationId = CONV_A,
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
            message = UserMessage(
                id = "msg-replay",
                contentRaw = JsonPrimitive("initial send"),
                date = "2026-09-10T16:00:00Z",
                otid = sentOtid,
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
        coordinator.send("send in flight").join()
        val sentOtid = timeline.externalLocals.single().otid

        // Remotely started turn arrives on CONV_SERVER without matching otid
        coordinator.handleEvent(
            WsTimelineEvent.TurnStarted(
                turnId = "turn-remote-1",
                agentId = AGENT_ID,
                conversationId = CONV_SERVER,
                runId = "run-remote-1",
            ),
        )

        val remoteAssistant = WsTimelineEvent.MessageDelta(
            message = AssistantMessage(
                id = "msg-remote-assistant",
                contentRaw = JsonPrimitive("remote reply"),
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
    }
}
