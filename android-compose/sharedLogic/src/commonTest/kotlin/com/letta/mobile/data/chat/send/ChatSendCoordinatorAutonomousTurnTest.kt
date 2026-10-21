package com.letta.mobile.data.chat.send

import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.transport.WsTimelineEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    private companion object {
        const val AGENT_ID = "agent-1"
        const val CONV_A = "conv-a"
        const val CONV_B = "conv-b"
    }
}
