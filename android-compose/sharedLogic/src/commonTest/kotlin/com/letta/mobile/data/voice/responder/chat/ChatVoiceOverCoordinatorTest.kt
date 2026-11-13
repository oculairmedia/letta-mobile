package com.letta.mobile.data.voice.responder.chat

import com.letta.mobile.data.chat.send.ChatSendCoordinatorConversationFixture
import com.letta.mobile.data.model.ConversationId
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The real voice responder through [ChatVoiceChatSession] over the real chat send coordinator. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatVoiceOverCoordinatorTest : ChatSendCoordinatorConversationFixture() {
    private val timeline = RecordingTimelineWriter()

    // The bridge deduplicates message frames process-wide, so this test's echoes need their own ids.
    private val echoPrefix = "voice-echo-${Random.nextLong()}"

    private fun echo(otid: String, turn: String, run: String) = WsTimelineEvent.MessageDelta(
        message = UserMessage(id = "$echoPrefix-$otid", contentRaw = JsonPrimitive("hi"), date = DATE, otid = otid, runId = run),
        conversationId = CONV,
        turnId = turn,
    )

    @Test
    fun theReplyFollowsItsOwnRunThroughPromotionAndNeverSpeaksAnotherClient() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        val coordinator = coordinator(timeline, RecordingUiSink(), transport) { CONV }
        val page = FakeChatPage()
        val session = ChatVoiceChatSession(page, coordinator.identifiedSend, MutableStateFlow(ConversationId(CONV)))
        val responder = VoiceChatResponder(session)
        val events = mutableListOf<VoiceReplyEvent>()
        val reply = responder.respond(VoiceUtterance(VoiceText("what time is it")))
        backgroundScope.launch { reply.events.collect { events += it } }
        advanceUntilIdle()
        val otid = timeline.externalLocals.single().otid

        page.show(userRow("u-x", "theirs"), assistantRow("m0", "Their answer.", run = "other-run"))
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT, CONV, "iroh-run-1"))
        coordinator.handleEvent(echo(otid, "turn-1", "iroh-run-1"))
        page.add(userRow(otid, "what time is it", sentAs = otid), assistantRow("m1", "It is", run = "iroh-run-1"))
        advanceUntilIdle()

        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-1", AGENT, CONV, "run-42"))
        page.replace(assistantRow("m1", "It is noon.", run = "run-42"))
        advanceUntilIdle()
        coordinator.handleEvent(WsTimelineEvent.TurnDone("turn-1", "run-42", BridgeTurnStatus.Completed))
        advanceUntilIdle()

        assertEquals(listOf("It is", " noon."), events.filterIsInstance<VoiceReplyEvent.Text>().map { it.delta.value })
        assertEquals(VoiceReplyEvent.Completed, events.last())
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV = "conv-1"
        const val DATE = "2026-11-12T10:00:00Z"
    }
}
