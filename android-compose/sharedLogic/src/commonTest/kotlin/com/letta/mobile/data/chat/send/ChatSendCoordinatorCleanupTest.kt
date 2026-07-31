package com.letta.mobile.data.chat.send

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendCoordinatorCleanupTest {
    @Test
    fun `next send heals stale presence when transport has no active turn`() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink(isStreaming = true, isAgentTyping = true)
            val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = false)
            val coordinator = coordinator(timeline, ui, transport)

            coordinator.send("next turn").join()

            assertEquals(listOf("next turn"), transport.sentTexts)
            assertEquals(1, ui.visualCompletions)
            // The new accepted send owns presence now; healing must not leave it settled.
            assertTrue(ui.isStreaming())
            assertTrue(ui.isAgentTyping())
        }

    @Test
    fun `new turn settles the previous turn unsettled optimistic-local otid`() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink()
            val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
            val coordinator = coordinator(timeline, ui, transport)

            // Turn 1 sends (sets activeWsOtid + appends an optimistic-local row)
            // but never receives a terminal to settle it.
            coordinator.send("first").join()
            val staleOtid = timeline.externalLocals.last().otid

            // A NEW server turn starts. Without PR-3 the old otid is orphaned and
            // re-latches Thinking; with it, the stale otid is settled as sent.
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-2", AGENT_ID, "conv-1", "run-2"))
            advanceUntilIdle()

            assertEquals(listOf(RecordingTimelineWriter.LocalMarker("conv-1", staleOtid)), timeline.sentLocals)
        }

    @Test
    fun `accepted send adopts new turn when coordinator still holds stale turn id`() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink()
            val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
            val recorded = mutableListOf<WsTimelineEvent>()
            val coordinator = coordinator(timeline, ui, transport, recordRuntimeEvent = { event, _ -> recorded += event })

            coordinator.send("first").join()
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-old", AGENT_ID, "conv-1", "run-old"))
            coordinator.send("second").join()
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-new", AGENT_ID, "conv-1", "run-new"))
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-new", "run-new", "completed"))
            advanceUntilIdle()

            assertTrue(recorded.contains(WsTimelineEvent.TurnStarted("turn-new", AGENT_ID, "conv-1", "run-new")))
            assertTrue(recorded.contains(WsTimelineEvent.TurnDone("turn-new", "run-new", "completed")))
            assertFalse(ui.isStreaming())
            assertFalse(ui.isAgentTyping())
        }

    @Test
    fun `next send does not clear presence while transport still owns active turn`() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink(isStreaming = true, isAgentTyping = true)
            val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
            val coordinator = coordinator(timeline, ui, transport)

            coordinator.send("queued turn").join()

            assertEquals(0, ui.visualCompletions)
        }
    @Test
    fun transientDisconnectDoesNotCleanupFailOrFinalizeActiveTurn() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(sendResults = mutableListOf(true))
        val coordinator = coordinator(timeline = timeline, ui = ui, transport = transport, activeConversationId = { "conv-1" })

        coordinator.send("hello").join()
        val local = timeline.externalLocals.single()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(WsTimelineEvent.Disconnected(code = 1006, reason = "network", willReconnect = true))
        advanceUntilIdle()

        assertTrue(ui.isStreaming())
        assertTrue(ui.isAgentTyping())
        assertNull(ui.currentError())
        assertEquals(listOf(true), ui.transientDisconnects)
        assertEquals(local.otid, timeline.externalLocals.single().otid)
        assertTrue(timeline.cleanupTails.isEmpty())
        assertTrue(timeline.failedLocals.isEmpty())
        assertTrue(timeline.clearedActiveConversations.isEmpty())
    }

    @Test
    fun terminalDisconnectWithActiveTurnCleansActiveRunTurnAndObservedAssistantRuns() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(timeline = timeline, ui = ui, transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" })

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-active"))
        coordinator.handleEvent(WsTimelineEvent.MessageDelta(AssistantMessage(id = "m1", contentRaw = JsonPrimitive("a"), runId = "run-real-1")))
        coordinator.handleEvent(WsTimelineEvent.MessageDelta(AssistantMessage(id = "m2", contentRaw = JsonPrimitive("b"), runId = "run-real-2")))
        coordinator.handleEvent(WsTimelineEvent.Disconnected(code = 1006, reason = "network lost"))
        advanceUntilIdle()

        assertEquals(
            RecordingTimelineWriter.CleanupTail(
                agentId = AGENT_ID,
                conversationId = "conv-1",
                activeRunId = "run-active",
                activeTurnId = "turn-1",
                candidateRunIds = setOf("run-active", "run-real-1", "run-real-2"),
            ),
            timeline.cleanupTails.single(),
        )
        assertEquals("network lost", ui.currentError())
        assertFalse(ui.isStreaming())
        assertEquals(listOf("conv-1"), timeline.clearedActiveConversations)
    }

    @Test
    fun terminalDisconnectWithNoActiveTurnDoesNotCleanupTailMessages() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink(isStreaming = true, isAgentTyping = true)
        val coordinator = coordinator(timeline = timeline, ui = ui, transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" })

        coordinator.handleEvent(WsTimelineEvent.Disconnected(code = 1006, reason = "gone"))
        advanceUntilIdle()

        assertTrue(timeline.cleanupTails.isEmpty())
        assertTrue(timeline.failedLocals.isEmpty())
        assertEquals("gone", ui.currentError())
        assertFalse(ui.isStreaming())
    }

    @Test
    fun cleanupFailureIsSuppressedAndTurnFinalizationContinues() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter(cleanupFailure = IllegalStateException("boom"))
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(sendResults = mutableListOf(true, true))
        val recorded = mutableListOf<WsTimelineEvent>()
        val coordinator = coordinator(
            timeline = timeline,
            ui = ui,
            transport = transport,
            activeConversationId = { "conv-1" },
            recordRuntimeEvent = { event, _ -> recorded += event },
        )

        coordinator.send("first").join()
        transport.sendResults += false
        coordinator.send("second").join()
        val active = timeline.externalLocals.last()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "failed"))
        advanceUntilIdle()

        assertEquals(listOf(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"), WsTimelineEvent.TurnDone("turn-1", "run-1", "failed")), recorded)
        assertEquals(listOf(RecordingTimelineWriter.LocalMarker("conv-1", active.otid)), timeline.failedLocals)
        // letta-mobile-br5g0: a reasonless dead turn now carries the generic
        // per-family copy instead of the bare "Turn failed".
        assertEquals(TurnFailureNotices.GENERIC_MESSAGE, ui.currentError())
        assertFalse(ui.isStreaming())
        // Two clears: the second send's stale-presence heal (the fake transport
        // reports no live turn while the UI still shows streaming) now settles the
        // orphaned row AND clears the transport-active marker — PR2 review finding
        // 5, previously it dropped the state and left the marker set — followed by
        // the terminal's own clear.
        assertEquals(listOf("conv-1", "conv-1"), timeline.clearedActiveConversations)
        assertEquals(1, transport.sentTexts.count { it == "second" })
    }

    @Test
    fun cleanupCancellationExceptionPropagates() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter(cleanupFailure = CancellationException("cancel cleanup"))
        val coordinator = coordinator(timeline = timeline, ui = RecordingUiSink(), transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" })

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))

        assertFailsWith<CancellationException> {
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "failed"))
        }
    }

    @Test
    fun failedTurnDoneWithSyntheticRunUsesObservedAssistantRunCandidates() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val coordinator = coordinator(timeline = timeline, ui = RecordingUiSink(), transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" })

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "synthetic-turn-run"))
        coordinator.handleEvent(WsTimelineEvent.MessageDelta(AssistantMessage(id = "m1", contentRaw = JsonPrimitive("a"), runId = "run-real")))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "synthetic-turn-run", "cancelled"))
        advanceUntilIdle()

        assertEquals(
            RecordingTimelineWriter.CleanupTail(
                agentId = AGENT_ID,
                conversationId = "conv-1",
                activeRunId = "synthetic-turn-run",
                activeTurnId = "turn-1",
                candidateRunIds = setOf("synthetic-turn-run", "run-real"),
            ),
            timeline.cleanupTails.single(),
        )
    }

    @Test
    fun `stale TurnDone for older turn does not finalize newer active turn`() = runTest(UnconfinedTestDispatcher()) {
        // dir4k (z5vfy PR-1) run/turn generation fence.
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(timeline = timeline, ui = ui, transport = FakeChannelTransport(mutableListOf(true, true)), activeConversationId = { "conv-1" })

        // Turn 1 starts, then the user starts a NEWER turn (turn-2) — turn-2 is
        // now the active turn. A late TurnDone for the OLD turn-1 arrives.
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-2", AGENT_ID, "conv-1", "run-2"))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "completed"))
        advanceUntilIdle()

        // The newer turn-2 must remain active and visually Thinking — the stale
        // terminal must NOT finalize it.
        assertTrue(ui.isStreaming())
        assertTrue(ui.isAgentTyping())
        // No finalization side effects for the newer turn: no active-conversation
        // clear (finishActiveTurn would have cleared it).
        assertTrue(timeline.clearedActiveConversations.isEmpty())
    }

    @Test
    fun `identified failed TurnDone before TurnStarted finalizes accepted send`() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(
            timeline = timeline,
            ui = ui,
            transport = FakeChannelTransport(mutableListOf(true)),
            activeConversationId = { "conv-1" },
        )

        coordinator.send("first").join()
        coordinator.handleEvent(WsTimelineEvent.Error("busy", "Send rejected", "conv-1", "turn-failed", "run-failed"))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-failed", "run-failed", "failed"))

        assertEquals(listOf(RecordingTimelineWriter.LocalMarker("conv-1", timeline.externalLocals.single().otid)), timeline.failedLocals)
        assertEquals("Send rejected", ui.currentError())
        assertFalse(ui.isStreaming())
        assertFalse(ui.isAgentTyping())
    }

    @Test
    fun `blank failed TurnDone before TurnStarted finalizes accepted send`() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(
            timeline = timeline,
            ui = ui,
            transport = FakeChannelTransport(mutableListOf(true)),
            activeConversationId = { "conv-1" },
        )

        coordinator.send("first").join()
        coordinator.handleEvent(WsTimelineEvent.Error("busy", "", "conv-1", null, null))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("", "", "failed"))

        assertEquals(listOf(RecordingTimelineWriter.LocalMarker("conv-1", timeline.externalLocals.single().otid)), timeline.failedLocals)
        assertEquals("busy", ui.currentError())
        assertFalse(ui.isStreaming())
        assertFalse(ui.isAgentTyping())
    }

    @Test
    fun `old TurnDone before newly accepted send starts cannot finalize the new generation`() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(
            timeline = timeline,
            ui = ui,
            transport = FakeChannelTransport(mutableListOf(true, true)),
            activeConversationId = { "conv-1" },
        )

        coordinator.send("first").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(WsTimelineEvent.SubscribeDone("run-1", lastSeq = 1L, status = "completed"))
        coordinator.send("second").join()

        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "completed"))
        assertTrue(ui.isStreaming())
        assertTrue(ui.isAgentTyping())
        assertEquals(1, timeline.clearedActiveConversations.size)

        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-2", AGENT_ID, "conv-1", "run-2"))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-2", "run-2", "completed"))
        assertFalse(ui.isStreaming())
        assertFalse(ui.isAgentTyping())
        assertEquals(2, timeline.clearedActiveConversations.size)
    }

    @Test
    fun `superseded TurnDone after later completion cannot finalize third accepted send`() =
        runTest(UnconfinedTestDispatcher()) {
            val timeline = RecordingTimelineWriter()
            val ui = RecordingUiSink()
            val coordinator = coordinator(
                timeline = timeline,
                ui = ui,
                transport = FakeChannelTransport(mutableListOf(true)),
                activeConversationId = { "conv-1" },
            )

            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-2", AGENT_ID, "conv-1", "run-2"))
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-2", "run-2", "completed"))
            coordinator.send("third").join()
            val thirdLocal = timeline.externalLocals.single()
            val clearsBeforeDelayedTerminal = timeline.clearedActiveConversations.size

            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "completed"))

            assertTrue(ui.isStreaming())
            assertTrue(ui.isAgentTyping())
            assertEquals(clearsBeforeDelayedTerminal, timeline.clearedActiveConversations.size)
            assertTrue(timeline.sentLocals.none { it.otid == thirdLocal.otid })
            assertTrue(timeline.failedLocals.none { it.otid == thirdLocal.otid })

            coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-3", AGENT_ID, "conv-1", "run-3"))
            coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-3", "run-3", "completed"))
            assertFalse(ui.isStreaming())
            assertFalse(ui.isAgentTyping())
            assertTrue(timeline.sentLocals.any { it.otid == thirdLocal.otid })
        }

    @Test
    fun `stale failed TurnDone for older turn cleans only the old run and keeps newer turn active`() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(timeline = timeline, ui = ui, transport = FakeChannelTransport(mutableListOf(true, true)), activeConversationId = { "conv-1" })

        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-2", AGENT_ID, "conv-1", "run-2"))
        // A late FAILED TurnDone for the old turn-1: old-run-scoped fragment
        // cleanup runs (scoped to run-1 only), but the newer turn stays active.
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "failed"))
        advanceUntilIdle()

        assertTrue(ui.isStreaming())
        assertTrue(ui.isAgentTyping())
        assertEquals(
            RecordingTimelineWriter.CleanupTail(
                agentId = AGENT_ID,
                conversationId = "conv-1",
                activeRunId = "run-1",
                activeTurnId = "turn-1",
                candidateRunIds = setOf("run-1"),
            ),
            timeline.cleanupTails.single(),
        )
        // Newer turn not finalized.
        assertTrue(timeline.clearedActiveConversations.isEmpty())
    }

    @Test
    fun `matching TurnDone for the current turn finalizes normally`() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(timeline = timeline, ui = ui, transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" })

        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "completed"))
        advanceUntilIdle()

        // The matching terminal finishes the current turn: presence cleared.
        assertFalse(ui.isStreaming())
        assertFalse(ui.isAgentTyping())
        assertEquals(listOf("conv-1"), timeline.clearedActiveConversations)
    }

    @Test
    fun postSendReconcileSkipsWhenLiveIngestArrivesAfterSend() = runTest(UnconfinedTestDispatcher()) {
        ChatSendCoordinator.postSendReconcileDelaysMs = longArrayOf(10L)
        val timeline = RecordingTimelineWriter()
        val coordinator = coordinator(timeline = timeline, ui = RecordingUiSink(), transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" }, scope = backgroundScope)

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(WsTimelineEvent.MessageDelta(AssistantMessage(id = "m1", contentRaw = JsonPrimitive("a"), runId = "run-1")))
        advanceTimeBy(11L)
        advanceUntilIdle()

        assertTrue(timeline.reconciles.isEmpty())
        ChatSendCoordinator.postSendReconcileDelaysMs = longArrayOf(750L, 2_500L, 6_000L)
    }

    @Test
    fun postSendReconcileStillRunsWithoutLiveIngest() = runTest(UnconfinedTestDispatcher()) {
        ChatSendCoordinator.postSendReconcileDelaysMs = longArrayOf(10L)
        val timeline = RecordingTimelineWriter()
        val coordinator = coordinator(timeline = timeline, ui = RecordingUiSink(), transport = FakeChannelTransport(mutableListOf(true)), activeConversationId = { "conv-1" }, scope = backgroundScope)

        coordinator.send("hello").join()
        advanceTimeBy(11L)
        advanceUntilIdle()

        assertEquals(listOf(RecordingTimelineWriter.Reconcile("conv-1", "post-send-10", true)), timeline.reconciles)
        ChatSendCoordinator.postSendReconcileDelaysMs = longArrayOf(750L, 2_500L, 6_000L)
    }

    // letta-mobile-br5g0: case 1 — a provider refusal killed the turn before any
    // assistant content was delivered. The user must see a visible error row
    // instead of a silently dead turn.
    @Test
    fun failedTurnWithoutDeliveredContentRendersErrorRow() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(timeline, ui, FakeChannelTransport(mutableListOf(true)))

        coordinator.send("hello").join()
        val otid = timeline.externalLocals.last().otid
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(
            WsTimelineEvent.Error(
                code = "app_server_turn_failed",
                message = "Model provider error: Provider finish_reason: content_filter",
                conversationId = "conv-1",
                turnId = "turn-1",
                runId = "run-1",
            ),
        )
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "failed"))
        advanceUntilIdle()

        val errorRow = timeline.ingestedMessages.filterIsInstance<ErrorMessage>().single()
        assertEquals("content_filter", errorRow.code)
        assertEquals(TurnFailureNotices.messageFor("content_filter"), errorRow.text)
        assertEquals("run-1", errorRow.runId)
        // The raw provider reason never rides the timeline row.
        assertFalse(errorRow.text.contains("finish_reason"))
        assertEquals(listOf(RecordingTimelineWriter.LocalMarker("conv-1", otid)), timeline.failedLocals)
    }

    // letta-mobile-br5g0: case 2 — the reply was delivered, then a trailing
    // aux-step (title/summary generation) failed and the run terminal came back
    // Failed. That must NOT look like a dead turn: no error row, no red prompt.
    @Test
    fun failedTurnAfterDeliveredContentIsNotPresentedAsDeadTurn() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(timeline, ui, FakeChannelTransport(mutableListOf(true)))

        coordinator.send("hello").join()
        val otid = timeline.externalLocals.last().otid
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(
            WsTimelineEvent.MessageDelta(
                AssistantMessage(id = "m1", contentRaw = JsonPrimitive("here is your answer"), runId = "run-1"),
            ),
        )
        // Main reply completed (end_turn) — later Failed is trailing aux work.
        coordinator.handleEvent(
            WsTimelineEvent.StopReason(turnId = "turn-1", runId = "run-1", stopReason = "end_turn"),
        )
        coordinator.handleEvent(
            WsTimelineEvent.Error(
                code = "content_filter",
                message = "Model provider error: Provider finish_reason: content_filter",
                conversationId = "conv-1",
                turnId = "turn-1",
                runId = "run-1",
            ),
        )
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "failed"))
        advanceUntilIdle()

        assertTrue(timeline.ingestedMessages.filterIsInstance<ErrorMessage>().isEmpty())
        assertTrue(timeline.failedLocals.isEmpty())
        assertTrue(timeline.sentLocals.contains(RecordingTimelineWriter.LocalMarker("conv-1", otid)))
        assertNull(ui.currentError())
    }

    // letta-mobile-br5g0 (codex review): the mapper ships the sanitized family
    // in Error.code with fixed copy in Error.message. Reclassifying the copy
    // downgraded content_filter to provider_error — the wire code must win.
    @Test
    fun errorFrameKnownCodeIsPreservedNotReclassifiedFromCopy() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(timeline, ui, FakeChannelTransport(mutableListOf(true)))

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(
            WsTimelineEvent.Error(
                code = "content_filter",
                // Current wire shape: fixed per-family copy, NOT the raw reason.
                // The copy says "refused", which terminalReasonKind does not
                // recognize — only the code carries the family.
                message = TurnFailureNotices.messageFor("content_filter"),
                conversationId = "conv-1",
                turnId = "turn-1",
                runId = "run-1",
            ),
        )
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "failed"))
        advanceUntilIdle()

        val errorRow = timeline.ingestedMessages.filterIsInstance<ErrorMessage>().single()
        assertEquals("content_filter", errorRow.code)
        assertEquals(TurnFailureNotices.messageFor("content_filter"), errorRow.text)
    }

    // letta-mobile-br5g0 (codex review P1): assistant deltas and completed
    // stops from ANOTHER conversation/turn (server-side fanout, replay) must
    // not count as this turn's delivery evidence — a foreign signal suppressed
    // the active turn's failure notice and marked the prompt sent.
    @Test
    fun foreignDeltaAndStopDoNotSuppressFailureNotice() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(timeline, ui, FakeChannelTransport(mutableListOf(true)))

        coordinator.send("hello").join()
        val otid = timeline.externalLocals.last().otid
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        // Fanned-out assistant delta from a different conversation and run.
        coordinator.handleEvent(
            WsTimelineEvent.MessageDelta(
                AssistantMessage(id = "mf", contentRaw = JsonPrimitive("foreign reply"), runId = "run-foreign"),
                conversationId = "conv-other",
            ),
        )
        // Completed stop from a different turn.
        coordinator.handleEvent(
            WsTimelineEvent.StopReason(turnId = "turn-other", runId = "run-foreign", stopReason = "end_turn"),
        )
        coordinator.handleEvent(
            WsTimelineEvent.Error(
                code = "content_filter",
                message = TurnFailureNotices.messageFor("content_filter"),
                conversationId = "conv-1",
                turnId = "turn-1",
                runId = "run-1",
            ),
        )
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "failed"))
        advanceUntilIdle()

        val errorRow = timeline.ingestedMessages.filterIsInstance<ErrorMessage>().single()
        assertEquals("content_filter", errorRow.code)
        assertEquals(listOf(RecordingTimelineWriter.LocalMarker("conv-1", otid)), timeline.failedLocals)
    }

    // letta-mobile-br5g0 (codex review P1): replayed deltas are history, not
    // proof this turn delivered anything.
    @Test
    fun replayedDeltaDoesNotCountAsDeliveredContent() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(timeline, ui, FakeChannelTransport(mutableListOf(true)))

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "run-1"))
        coordinator.handleEvent(
            WsTimelineEvent.MessageDelta(
                AssistantMessage(id = "m1", contentRaw = JsonPrimitive("old reply"), runId = "run-1"),
                isReplay = true,
                conversationId = "conv-1",
            ),
        )
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-1", "failed"))
        advanceUntilIdle()

        assertEquals(1, timeline.ingestedMessages.filterIsInstance<ErrorMessage>().size)
    }

    // letta-mobile-br5g0 (codex review P2): run ids reset across App Server
    // restarts, so the failure row is keyed by (turn, run) — a later failure
    // reusing local-run-1 must produce a distinct row.
    @Test
    fun failureRowIdIncludesTurnIdentity() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val coordinator = coordinator(timeline, ui, FakeChannelTransport(mutableListOf(true)))

        coordinator.send("hello").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT_ID, "conv-1", "local-run-1"))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "local-run-1", "failed"))
        advanceUntilIdle()

        val errorRow = timeline.ingestedMessages.filterIsInstance<ErrorMessage>().single()
        assertEquals("turn-failed-turn-1-local-run-1", errorRow.id)
    }

    private fun coordinator(
        timeline: RecordingTimelineWriter,
        ui: RecordingUiSink,
        transport: FakeChannelTransport,
        activeConversationId: () -> String? = { "conv-1" },
        recordRuntimeEvent: suspend (WsTimelineEvent, String?) -> Unit = { _, _ -> },
        scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
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
        recordRuntimeEvent = recordRuntimeEvent,
    )

    private class RecordingUiSink(
        private var error: String? = null,
        private var isStreaming: Boolean = false,
        private var isAgentTyping: Boolean = false,
    ) : ChatSendUiSink {
        val transientDisconnects = mutableListOf<Boolean>()
        var visualCompletions = 0
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
        override fun onTurnFinished(error: String?) { this.error = error; isStreaming = false; isAgentTyping = false }
        override fun onTurnVisuallyComplete() { visualCompletions++; isStreaming = false; isAgentTyping = false }
        override fun onTransientDisconnect(hasActiveSend: Boolean) { transientDisconnects += hasActiveSend; error = null; isStreaming = hasActiveSend; isAgentTyping = hasActiveSend }
        override fun onDisconnectFailure(error: String) { this.error = error; isStreaming = false; isAgentTyping = false }
    }

    private class RecordingTimelineWriter(private val cleanupFailure: Throwable? = null) : TimelineExternalTransportWriter {
        val externalLocals = mutableListOf<ExternalLocal>()
        val ingestedMessages = mutableListOf<LettaMessage>()
        val sentLocals = mutableListOf<LocalMarker>()
        val failedLocals = mutableListOf<LocalMarker>()
        val clearedActiveConversations = mutableListOf<String>()
        val cleanupTails = mutableListOf<CleanupTail>()
        val reconciles = mutableListOf<Reconcile>()
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
        override suspend fun cleanupAbandonedAssistantFragments(agentId: String?, conversationId: String, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String>): Int { cleanupFailure?.let { throw it }; cleanupTails += CleanupTail(agentId, conversationId, runId, turnId, candidateRunIds); return 0 }
        override suspend fun reconcileRecentMessages(agentId: String?, conversationId: String, reason: String, forceRefresh: Boolean): Int { reconciles += Reconcile(conversationId, reason, forceRefresh); return 0 }
        data class ExternalLocal(val conversationId: String, val content: String, val otid: String)
        data class LocalMarker(val conversationId: String, val otid: String)
        data class CleanupTail(val agentId: String?, val conversationId: String, val activeRunId: String?, val activeTurnId: String?, val candidateRunIds: Set<String>)
        data class Reconcile(val conversationId: String, val reason: String, val forceRefresh: Boolean)
    }

    /**
     * letta-mobile-or40x PR2: the fake now answers turn ownership PER
     * CONVERSATION (mirroring the keyed transport from PR1), so tests can put
     * conversation B's turn in flight without claiming conversation A is busy.
     * [activeChatTurn] stays as the "every conversation is busy" shorthand the
     * older single-conversation tests use.
     */
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

    private companion object {
        const val AGENT_ID = "agent-1"
        var otid = 0
    }
}
