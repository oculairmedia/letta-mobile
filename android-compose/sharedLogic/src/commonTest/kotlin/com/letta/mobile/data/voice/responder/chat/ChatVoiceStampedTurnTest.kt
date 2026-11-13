package com.letta.mobile.data.voice.responder.chat

import com.letta.mobile.data.chat.send.ChatSendCoordinatorConversationFixture
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import com.letta.mobile.data.voice.responder.VoiceChatResponder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real voice responder over the real coordinator, fed the event order the desktop's Iroh route
 * produced in a live run: the turn's first TurnStarted is handled only after the send was accepted
 * (so the coordinator settles the send's otid as "stale" at TurnStarted), the send's echo may never
 * arrive, the durable run promotes the provisional one, every row is stamped with the send's otid
 * as its turn, and the run id rotates inside the turn (tool step on one run, reply on the next).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatVoiceStampedTurnTest : ChatSendCoordinatorConversationFixture() {
    private val timeline = RecordingTimelineWriter()

    // The bridge deduplicates message frames process-wide, so this test's frames need their own ids.
    private val prefix = "voice-stamped-${Random.nextLong()}"

    private fun row(message: com.letta.mobile.data.model.LettaMessage, turn: String) =
        WsTimelineEvent.MessageDelta(message = message, conversationId = CONV, turnId = turn)

    private fun assistant(id: String, text: String, run: String, turn: String) =
        row(AssistantMessage(id = "$prefix-$id", contentRaw = JsonPrimitive(text), date = DATE, runId = run, turnId = turn), turn)

    private fun toolCall(run: String, turn: String) =
        row(ToolCallMessage(id = "$prefix-tc", date = DATE, runId = run, turnId = turn), turn)

    private fun echo(otid: String, run: String): WsTimelineEvent.MessageDelta {
        val message = UserMessage(
            id = "$prefix-echo-$otid",
            contentRaw = JsonPrimitive("hi"),
            date = DATE,
            otid = otid,
            runId = run,
            turnId = otid,
        )
        return row(message, otid)
    }

    private class Harness(val page: FakeChatPage, val events: MutableList<VoiceReplyEvent>, val otid: String)

    private fun TestScope.start(): Pair<Harness, com.letta.mobile.data.chat.send.ChatSendCoordinator> {
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        val coordinator = coordinator(timeline, RecordingUiSink(), transport) { CONV }
        val page = FakeChatPage()
        val session = ChatVoiceChatSession(page, coordinator.identifiedSend, MutableStateFlow(ConversationId(CONV)))
        val events = mutableListOf<VoiceReplyEvent>()
        val reply = VoiceChatResponder(session).respond(VoiceUtterance(VoiceText("what time is it")))
        backgroundScope.launch { reply.events.collect { events += it } }
        advanceUntilIdle()
        return Harness(page, events, timeline.externalLocals.single().otid) to coordinator
    }

    @Test
    fun aReplyWhoseEchoNeverArrivesIsSpokenFromRowsStampedWithTheSendsOtid() = runTest(UnconfinedTestDispatcher()) {
        val (h, coordinator) = start()
        h.page.show(userRow(h.otid, "what time is it", sentAs = h.otid))

        // Handled after the accept: the coordinator settles the otid as stale here.
        coordinator.handleEvent(WsTimelineEvent.TurnStarted(TURN, AGENT, CONV, "iroh-run-1"))
        coordinator.handleEvent(WsTimelineEvent.TurnStarted(TURN, AGENT, CONV, "local-run-55"))
        coordinator.handleEvent(toolCall(run = "local-run-55", turn = h.otid))
        // Another client's turn on the same conversation: stamped with its own otid.
        coordinator.handleEvent(assistant("theirs", "Not yours.", run = "other-run", turn = "their-otid"))
        h.page.add(assistantRow("theirs", "Not yours.", run = "other-run"))
        coordinator.handleEvent(assistant("m1", "It is", run = "local-run-57", turn = h.otid))
        h.page.add(assistantRow("m1", "It is", run = "local-run-57"))
        advanceUntilIdle()
        coordinator.handleEvent(assistant("m1", "It is noon.", run = "local-run-57", turn = h.otid))
        h.page.replace(assistantRow("m1", "It is noon.", run = "local-run-57"))
        advanceUntilIdle()
        coordinator.handleEvent(WsTimelineEvent.TurnDone(TURN, "local-run-55", BridgeTurnStatus.Completed))
        advanceUntilIdle()

        assertEquals(listOf("It is", " noon."), h.events.filterIsInstance<VoiceReplyEvent.Text>().map { it.delta.value })
        assertEquals(VoiceReplyEvent.Completed, h.events.last())
    }

    @Test
    fun anEchoStampedWithTheOtidAsItsTurnStillLetsTheRotatedReplyRunBeSpoken() = runTest(UnconfinedTestDispatcher()) {
        val (h, coordinator) = start()
        coordinator.handleEvent(WsTimelineEvent.TurnStarted(TURN, AGENT, CONV, "iroh-run-1"))
        coordinator.handleEvent(echo(h.otid, run = "iroh-run-1"))
        h.page.show(userRow(h.otid, "what time is it", sentAs = h.otid))
        coordinator.handleEvent(WsTimelineEvent.TurnStarted(TURN, AGENT, CONV, "local-run-55"))
        coordinator.handleEvent(toolCall(run = "local-run-55", turn = h.otid))
        coordinator.handleEvent(assistant("m1", "Noon.", run = "local-run-57", turn = h.otid))
        h.page.add(assistantRow("m1", "Noon.", run = "local-run-57"))
        advanceUntilIdle()
        coordinator.handleEvent(WsTimelineEvent.TurnDone(TURN, "local-run-55", BridgeTurnStatus.Completed))
        advanceUntilIdle()

        assertEquals(listOf("Noon."), h.events.filterIsInstance<VoiceReplyEvent.Text>().map { it.delta.value })
        assertEquals(VoiceReplyEvent.Completed, h.events.last())
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV = "conv-1"
        const val TURN = "iroh-turn-6c6f3617"
        const val DATE = "2026-11-12T10:00:00Z"
    }
}
