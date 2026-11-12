package com.letta.mobile.data.chat.send

import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.transport.WsTimelineEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatSendCoordinatorAutonomousTurnTest : ChatSendCoordinatorConversationFixture() {
    @Test
    fun autonomousObserverTurnWithoutTurnStartedSettlesOwnTimelineOnTurnDone() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        var active: String? = CONV_A
        val coordinator = coordinator(timeline, ui, transport) { active }

        // 1. Complete a local turn on CONV_A first so turnStates has CONV_A and lastActiveConversationId = CONV_A
        coordinator.send("hello A").join()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-a", "run-a", BridgeTurnStatus.Completed))
        advanceUntilIdle()
        timeline.clearedActiveConversations.clear()
        ui.turnsFinished.clear()

        // 2. Switch to CONV_B; an autonomous observer turn streams deltas on CONV_B with NO TurnStarted
        active = CONV_B
        coordinator.handleEvent(
            WsTimelineEvent.MessageDelta(
                message = AssistantMessage(
                    id = "msg-auto-b",
                    contentRaw = JsonPrimitive("autonomous subagent completion reply"),
                    runId = "run-auto-b",
                ),
                conversationId = CONV_B,
                turnId = "turn-auto-b",
                agentId = AGENT_ID,
            ),
        )
        assertTrue(ui.isStreaming(), "autonomous delta on visible conversation must set streaming")

        // 3. Observer emits TurnDone for the autonomous turn -> must settle CONV_B (not CONV_A, not staleIgnored)
        coordinator.handleEvent(
            WsTimelineEvent.TurnDone(
                turnId = "turn-auto-b",
                runId = "run-auto-b",
                status = BridgeTurnStatus.Completed,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(CONV_B), timeline.clearedActiveConversations)
        assertEquals(1, ui.turnsFinished.size)
        assertTrue(!ui.isStreaming(), "streaming must clear when autonomous turn finishes")
    }

    @Test
    fun completedReplayOfUnobservedWakeReleasesHeldSendImmediately() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
        val chat = coordinator(RecordingTimelineWriter(), RecordingUiSink(), transport, backgroundScope) { CONV_A }
        chat.send("waiting input").join()
        chat.handleEvent(WsTimelineEvent.TurnStarted("local-attempt", AGENT_ID, CONV_A, "local-attempt-run"))
        chat.handleEvent(WsTimelineEvent.Error(
            code = "iroh_turn_engine_busy", message = "turn already active",
            conversationId = CONV_A, turnId = "local-attempt", runId = "local-attempt-run",
        ))
        chat.handleEvent(WsTimelineEvent.TurnDone("local-attempt", "local-attempt-run", BridgeTurnStatus.Failed))
        assertTrue(chat.sendQueue.state.value[QueueConversationId(CONV_A)]?.heldByOtherClient == true)
        transport.activeChatTurn = false

        // Reconnecting viewers can receive only the terminal replay fence, not the wake's start.
        chat.handleEvent(WsTimelineEvent.SubscribeDone("unobserved-wake-run", 12, "completed"))

        assertEquals(listOf("waiting input", "waiting input"), transport.sentTexts,
            "a completed replay must release the hold without waiting for the backstop or Send now")
    }

    @Test
    fun heldSendStillObservesIdleAfterBackstopFoundWakeRunning() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
        val chat = coordinator(RecordingTimelineWriter(), RecordingUiSink(), transport, backgroundScope) { CONV_A }
        chat.send("waiting input").join()
        chat.handleEvent(WsTimelineEvent.TurnStarted("local-attempt", AGENT_ID, CONV_A, "local-attempt-run"))
        chat.handleEvent(WsTimelineEvent.Error(
            code = "iroh_turn_engine_busy", message = "turn already active",
            conversationId = CONV_A, turnId = "local-attempt", runId = "local-attempt-run",
        ))
        chat.handleEvent(WsTimelineEvent.TurnDone("local-attempt", "local-attempt-run", BridgeTurnStatus.Failed))
        advanceTimeBy(5_000)
        transport.activeChatTurn = false
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(listOf("waiting input", "waiting input"), transport.sentTexts,
            "a missed wake terminal must not spend the only backstop while transport is still busy")
    }

    @Test
    fun idlePresenceReleasesHoldWithoutTheWakeTerminal() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
        val chat = coordinator(RecordingTimelineWriter(), RecordingUiSink(), transport, backgroundScope) { CONV_A }
        chat.send("waiting input").join()
        chat.handleEvent(WsTimelineEvent.TurnStarted("local-attempt", AGENT_ID, CONV_A, "local-attempt-run"))
        chat.handleEvent(WsTimelineEvent.Error(
            code = "iroh_turn_engine_busy", message = "turn already active",
            conversationId = CONV_A, turnId = "local-attempt", runId = "local-attempt-run",
        ))
        chat.handleEvent(WsTimelineEvent.TurnDone("local-attempt", "local-attempt-run", BridgeTurnStatus.Failed))
        transport.activeChatTurn = false
        chat.handleEvent(WsTimelineEvent.RunActivity(
            com.letta.mobile.runtime.RuntimeEventPayload.LoopPhaseChanged("WAITING_ON_INPUT"),
            agentId = AGENT_ID, conversationId = CONV_A, runId = "wake-run",
        ))
        assertEquals(listOf("waiting input", "waiting input"), transport.sentTexts)
    }

    @Test
    fun missedWakeEndWithNoReplayOrLoopStatusStillReleasesOnBackstop() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
        val chat = coordinator(RecordingTimelineWriter(), RecordingUiSink(), transport, backgroundScope) { CONV_A }
        chat.send("waiting input").join()
        chat.handleEvent(WsTimelineEvent.TurnStarted("local-attempt", AGENT_ID, CONV_A, "local-attempt-run"))
        chat.handleEvent(WsTimelineEvent.Error(
            code = "iroh_turn_engine_busy", message = "turn already active",
            conversationId = CONV_A, turnId = "local-attempt", runId = "local-attempt-run",
        ))
        chat.handleEvent(WsTimelineEvent.TurnDone("local-attempt", "local-attempt-run", BridgeTurnStatus.Failed))
        // The local attempted turn has retired. A foreign wake is never in this device's registry.
        transport.activeChatTurn = false
        // No foreign terminal, loop status, subscription completion, or other event is delivered.
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(listOf("waiting input", "waiting input"), transport.sentTexts)
    }

    @Test
    fun rejectedRetryAndSuspendedTimelineAppendAlwaysHaveExactlyOneVisiblePrompt() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val transport = FakeChannelTransport(mutableListOf(false, false, true), activeChatTurn = true)
        val chat = coordinator(timeline, RecordingUiSink(), transport, backgroundScope) { CONV_A }
        chat.send("waiting input").join()
        val visibleCounts = mutableListOf<Int>()
        fun visibleCount(): Int {
            val queue = chat.sendQueue.state.value[QueueConversationId(CONV_A)] ?: ConversationSendQueue()
            val queued = queue.items.map { it.id.value } + listOfNotNull(queue.queuedOnServer?.id?.value)
            val transcript = timeline.externalLocals.map { it.otid }.distinct().count { it !in queued }
            return queued.size + transcript
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            chat.sendQueue.state.collect { visibleCounts += visibleCount() }
        }
        transport.activeChatTurn = false
        chat.sendQueue.resume(QueueConversationId(CONV_A)).join()
        assertEquals(1, visibleCount(), "a rejected retry must remain represented during drain backoff")
        timeline.appendStarted = CompletableDeferred()
        timeline.appendRelease = CompletableDeferred()
        advanceTimeBy(51)
        runCurrent()
        assertTrue(timeline.appendStarted!!.isCompleted)
        assertEquals(1, visibleCount(), "accepted transport must remain represented while timeline publication suspends")
        timeline.appendRelease!!.complete(Unit)
        runCurrent()
        assertEquals(1, visibleCount(), "published prompt must move from queue to transcript without duplication")
        assertTrue(visibleCounts.all { it == 1 }, "every queue publication must retain one prompt: $visibleCounts")
        assertTrue(chat.sendQueue.state.value[QueueConversationId(CONV_A)]?.isEmpty != false)
        assertEquals("waiting input", timeline.externalLocals.single().content)
    }

    private companion object {
        const val AGENT_ID = "agent-1"
        const val CONV_A = "conv-a"
        const val CONV_B = "conv-b"
    }
}
