package com.letta.mobile.data.voice.responder.chat

import com.letta.mobile.data.chat.send.ChatSendCoordinator
import com.letta.mobile.data.chat.send.ChatSendCoordinatorConversationFixture
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.sentruns.SentMessageId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import com.letta.mobile.data.voice.responder.VoiceChatResponder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The real voice responder through [ChatVoiceChatSession] over the real chat send coordinator, for a
 * second utterance: spoken while the first answer is awaited it is a follow-up the coordinator
 * queues behind the run in flight, and both answers are spoken; sent right after a stop it is
 * never swept away with the stopped run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatVoiceFollowUpOverCoordinatorTest : ChatSendCoordinatorConversationFixture() {
    private val timeline = RecordingTimelineWriter()

    // The bridge deduplicates message frames process-wide, so this test's frames need their own ids.
    private val prefix = "voice-follow-up-${Random.nextLong()}"

    /** A frame's run and the turn (the send's otid) it is stamped with. */
    private data class Stamp(val run: String, val turn: String)

    private fun assistant(id: String, text: String, stamp: Stamp) = WsTimelineEvent.MessageDelta(
        message = AssistantMessage(
            id = "$prefix-$id",
            contentRaw = JsonPrimitive(text),
            date = DATE,
            runId = stamp.run,
            turnId = stamp.turn,
        ),
        conversationId = CONV,
        turnId = stamp.turn,
    )

    private fun toolCall(stamp: Stamp) = WsTimelineEvent.MessageDelta(
        message = ToolCallMessage(id = "$prefix-tc", date = DATE, runId = stamp.run, turnId = stamp.turn),
        conversationId = CONV,
        turnId = stamp.turn,
    )

    private class Rig(
        val coordinator: ChatSendCoordinator,
        val transport: FakeChannelTransport,
        val page: FakeChatPage,
        val responder: VoiceChatResponder,
    )

    /** The coordinator, and a page that shows its rows and (as the chat page does) the coordinator's send queue. */
    private fun TestScope.rig(sends: MutableList<Boolean> = mutableListOf(true, false)): Rig {
        val transport = FakeChannelTransport(sends, activeChatTurn = true)
        val coordinator = coordinator(timeline, RecordingUiSink(), transport) { CONV }
        val page = FakeChatPage()
        backgroundScope.launch {
            coordinator.sendQueue.state.collect { queues -> page.showQueue(queues[QueueConversationId(CONV)] ?: ConversationSendQueue()) }
        }
        val session = ChatVoiceChatSession(page, coordinator.identifiedSend, MutableStateFlow(ConversationId(CONV)))
        return Rig(coordinator, transport, page, VoiceChatResponder(session))
    }

    private fun TestScope.collect(reply: VoiceReply): MutableList<VoiceReplyEvent> {
        val events = mutableListOf<VoiceReplyEvent>()
        backgroundScope.launch { reply.events.collect { events += it } }
        advanceUntilIdle()
        return events
    }

    private fun Rig.ask(words: String): VoiceReply = responder.respond(VoiceUtterance(VoiceText(words)))

    private fun texts(events: List<VoiceReplyEvent>) = events.filterIsInstance<VoiceReplyEvent.Text>().map { it.delta.value }

    /** The coordinator's queued sends for the conversation. */
    private fun Rig.queued(): List<String> =
        coordinator.sendQueue.state.value[QueueConversationId(CONV)]?.items.orEmpty().map { it.id.value }

    /** One host turn: its id, the otid of the send that started it, and the run its reply streams on. */
    private data class HostTurn(val turn: String, val otid: String, val run: String)

    /** The run the turn's send started streams [words], stamped with the send's otid, and the turn ends. */
    private suspend fun TestScope.answer(rig: Rig, host: HostTurn, words: String) {
        val (turn, otid, run) = host
        rig.coordinator.handleEvent(WsTimelineEvent.TurnStarted(turn, AGENT, CONV, run))
        rig.coordinator.handleEvent(assistant("$turn-m", words, Stamp(run, otid)))
        rig.page.add(assistantRow(RowId("$turn-m"), words, run = RowRun(run)))
        advanceUntilIdle()
        rig.transport.activeChatTurn = false
        rig.coordinator.handleEvent(WsTimelineEvent.TurnDone(turn, run, BridgeTurnStatus.Completed))
        advanceUntilIdle()
    }

    /**
     * The live run's order: the first send is accepted and the host starts its turn late; a second
     * utterance while waiting is queued by the coordinator behind that run; nothing stops anything;
     * the first run's rows (stamped with the first otid) are spoken, its turn ends, the queue drains,
     * and the follow-up's run is spoken too. Time spent queued never counts as undelivered.
     */
    @Test
    fun aFollowUpQueuedBehindTheRunInFlightIsDeliveredAndBothAnswersAreSpoken() = runTest(UnconfinedTestDispatcher()) {
        val rig = rig()
        val first = collect(rig.ask("what is the weather like in town today"))
        val firstOtid = timeline.externalLocals.single().otid
        rig.page.show(userRow(RowId(firstOtid), "what is the weather like in town today", sentAs = SentMessageId(firstOtid)))
        rig.coordinator.handleEvent(WsTimelineEvent.TurnStarted(TURN_1, AGENT, CONV, "iroh-run-1"))
        rig.coordinator.handleEvent(toolCall(Stamp("local-run-400", firstOtid)))

        val second = collect(rig.ask("hey"))
        val secondOtid = rig.queued().single()
        assertEquals(0, rig.page.stops)
        advanceTimeBy(40.seconds)
        assertEquals(emptyList(), second, "a follow-up waiting behind the first run is not undelivered")

        answer(rig, HostTurn(TURN_1, firstOtid, "local-run-401"), "Sunny and mild.")
        assertEquals(emptyList(), rig.queued())
        assertEquals(secondOtid, timeline.externalLocals.last().otid)
        rig.page.add(userRow(RowId(secondOtid), "hey", sentAs = SentMessageId(secondOtid)))
        rig.transport.activeChatTurn = true
        answer(rig, HostTurn(TURN_2, secondOtid, "local-run-402"), "Hello again.")

        assertEquals(listOf("Sunny and mild."), texts(first))
        assertEquals(VoiceReplyEvent.Completed, first.last())
        assertEquals(listOf("Hello again."), texts(second))
        assertEquals(VoiceReplyEvent.Completed, second.last())
        assertEquals(0, rig.page.stops)
    }

    /** Barge-in's order: the reply's run is stopped through the page, the stop settles, then the new utterance is sent. */
    @Test
    fun aSendAfterASettledStopIsDispatchedAndAnswered() = runTest(UnconfinedTestDispatcher()) {
        val rig = rig(sends = mutableListOf(true))
        rig.page.onStop = { rig.coordinator.cancel() }
        val (first, firstOtid) = speakingFirstAnswer(rig)

        val outcome = cancelLater(first)
        assertEquals(1, rig.page.stops)
        rig.transport.activeChatTurn = false
        rig.coordinator.handleEvent(WsTimelineEvent.TurnDone(TURN_1, "local-run-7", BridgeTurnStatus.Cancelled))
        advanceUntilIdle()
        assertEquals(VoiceCancelOutcome.STOPPED, outcome())

        val second = collect(rig.ask("never mind"))
        val secondOtid = timeline.externalLocals.last().otid
        assertTrue(secondOtid != firstOtid)
        assertEquals(emptyList(), rig.queued())
        rig.page.add(userRow(RowId(secondOtid), "never mind", sentAs = SentMessageId(secondOtid)))
        rig.transport.activeChatTurn = true
        answer(rig, HostTurn(TURN_2, secondOtid, "local-run-8"), "Okay.")
        assertEquals(listOf("Okay."), texts(second))
        assertEquals(VoiceReplyEvent.Completed, second.last())
    }

    /**
     * The stop did not settle within the loop's wait, so the new utterance is sent while the stopped
     * run is still in flight: the coordinator queues it, the Stop's pause does not hold it, and it is
     * dispatched once the stopped turn ends (never cancelled from the queue).
     */
    @Test
    fun aSendRightAfterAStopIsQueuedNotSweptAndRunsOnceTheStoppedTurnEnds() = runTest(UnconfinedTestDispatcher()) {
        val rig = rig()
        rig.page.onStop = { rig.coordinator.cancel() }
        val (first, _) = speakingFirstAnswer(rig)

        cancelLater(first)
        val second = collect(rig.ask("never mind"))
        val secondOtid = rig.queued().single()
        val queue = rig.coordinator.sendQueue.state.value.getValue(QueueConversationId(CONV))
        assertEquals(false, queue.paused, "a new send releases the queue the Stop paused")

        rig.transport.activeChatTurn = false
        rig.coordinator.handleEvent(WsTimelineEvent.TurnDone(TURN_1, "local-run-7", BridgeTurnStatus.Cancelled))
        advanceUntilIdle()
        assertEquals(emptyList(), rig.queued())
        assertEquals(secondOtid, timeline.externalLocals.last().otid)
        assertEquals(listOf("never mind", "never mind"), rig.transport.sentTexts.drop(1))
        rig.page.add(userRow(RowId(secondOtid), "never mind", sentAs = SentMessageId(secondOtid)))
        rig.transport.activeChatTurn = true
        answer(rig, HostTurn(TURN_2, secondOtid, "local-run-8"), "Okay.")
        assertEquals(listOf("Okay."), texts(second))
        assertEquals(VoiceReplyEvent.Completed, second.last())
    }

    /** The first reply sent, its run started and its first sentence streamed (stamped with its otid). */
    private suspend fun TestScope.speakingFirstAnswer(rig: Rig): Pair<VoiceReply, String> {
        val first = rig.ask("tell me a story")
        val events = collect(first)
        val otid = timeline.externalLocals.single().otid
        rig.page.show(userRow(RowId(otid), "tell me a story", sentAs = SentMessageId(otid)))
        rig.coordinator.handleEvent(WsTimelineEvent.TurnStarted(TURN_1, AGENT, CONV, "iroh-run-1"))
        rig.coordinator.handleEvent(WsTimelineEvent.TurnStarted(TURN_1, AGENT, CONV, "local-run-7"))
        rig.coordinator.handleEvent(assistant("story", "Once upon a time.", Stamp("local-run-7", otid)))
        rig.page.add(assistantRow(RowId("story"), "Once upon a time.", run = RowRun("local-run-7")))
        advanceUntilIdle()
        assertEquals(listOf("Once upon a time."), texts(events))
        return first to otid
    }

    /** Starts [reply]'s cancel in the background; the outcome is null until the cancel returns. */
    private fun TestScope.cancelLater(reply: VoiceReply): () -> VoiceCancelOutcome? {
        var outcome: VoiceCancelOutcome? = null
        backgroundScope.launch { outcome = reply.cancel() }
        advanceUntilIdle()
        return { outcome }
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV = "conv-1"
        const val TURN_1 = "iroh-turn-1"
        const val TURN_2 = "iroh-turn-2"
        const val DATE = "2026-11-12T10:00:00Z"
    }
}
