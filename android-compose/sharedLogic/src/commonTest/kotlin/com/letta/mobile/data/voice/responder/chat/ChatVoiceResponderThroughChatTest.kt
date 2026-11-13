package com.letta.mobile.data.voice.responder.chat

import com.letta.mobile.data.chat.send.ChatSendQueue
import com.letta.mobile.data.chat.send.ConversationTurnRegistry
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedChatSend
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.chat.send.TurnSettlementOwnership
import com.letta.mobile.data.chat.send.TurnTail
import com.letta.mobile.data.chat.send.advanceStartedIdentity
import com.letta.mobile.data.chat.send.recordStarted
import com.letta.mobile.data.chat.sentruns.SentMessageId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import com.letta.mobile.data.voice.responder.VoiceChatResponder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The real voice responder over [ChatVoiceChatSession], with the sent runs published by the chat
 * send pipeline's own turn state (echo binding, run promotion, terminal clear) rather than a script.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatVoiceResponderThroughChatTest {
    private val registry = ConversationTurnRegistry { ConversationId(CONVERSATION) }
    private val state = registry.stateFor(ConversationId(CONVERSATION))
    private val ownership = TurnSettlementOwnership(registry, ChatSendQueue())
    private val page = FakeChatPage().apply { onStop = { ownership.clear(state, reason = "stopped") } }
    private val sender = FakeIdentifiedSend(registry.sentRuns.state, onSend = ::accept)
    private val responder = VoiceChatResponder(ChatVoiceChatSession(page, sender, MutableStateFlow(ConversationId(CONVERSATION))))

    /** The pipeline dispatches the send at once, as on an idle conversation. */
    private fun accept(id: SentMessageId) {
        val pending = QueuedChatSend(QueuedSendId(id.value), QueueConversationId(CONVERSATION), "spoken")
        state.acceptSend(pending, send = { true }, onAccepted = {})
    }

    /** What the projection does with a TurnStarted frame. */
    private fun turnStarted(turn: String, run: String) {
        val event = WsTimelineEvent.TurnStarted(turn, AGENT, CONVERSATION, run)
        if (!state.advanceStartedIdentity(event)) state.recordStarted(event)
    }

    /** The send's own echo, pairing its otid with the run. */
    private fun echo(otid: String, turn: String, run: String) {
        registry.bindInboundTurnByOtid(QueuedSendId(otid), null, TurnTail(turn, run))
    }

    private fun complete() = ownership.clear(state, reason = "turnFinished")

    private fun fail() {
        state.ownRun.end(failed = true)
        ownership.clear(state, reason = "turnFinished")
    }

    private fun ask(words: String): VoiceReply = responder.respond(VoiceUtterance(VoiceText(words)))

    private fun TestScope.collect(reply: VoiceReply): MutableList<VoiceReplyEvent> {
        val events = mutableListOf<VoiceReplyEvent>()
        backgroundScope.launch { reply.events.collect { events += it } }
        runCurrent()
        return events
    }

    private fun TestScope.settle(change: () -> Unit) {
        change()
        runCurrent()
    }

    private fun texts(events: List<VoiceReplyEvent>) = events.filterIsInstance<VoiceReplyEvent.Text>().map { it.delta.value }

    private fun failed(code: VoiceErrorCode) = VoiceReplyEvent.Failed(VoiceError(code, VoicePort.RESPONDER))

    /** The second send used to stop the first run (one Stop press); as a follow-up it now stops nothing. */
    @Test
    fun overlappingSendsEachHearOnlyTheirOwnRun() = runTest {
        val first = collect(ask("first"))
        settle {
            turnStarted("t1", "run-1")
            echo("c1", "t1", "run-1")
            page.add(userRow("c1", "first", sentAs = "c1"), assistantRow("m1", "One.", run = "run-1"))
        }

        val second = collect(ask("second"))
        assertEquals(0, page.stops)
        settle { page.replace(assistantRow("m1", "One. And a late tail.", run = "run-1")) }
        settle {
            turnStarted("t2", "run-2")
            echo("c2", "t2", "run-2")
            page.add(userRow("c2", "second", sentAs = "c2"), assistantRow("m2", "Two.", run = "run-2"))
        }
        settle { complete() }

        assertEquals(listOf("first", "second"), sender.texts)
        assertEquals(listOf("One."), texts(first))
        assertEquals(listOf("Two."), texts(second))
        assertEquals(VoiceReplyEvent.Completed, second.last())
    }

    @Test
    fun aRunPromotedMidStreamKeepsBeingFollowed() = runTest {
        val events = collect(ask("what time is it"))
        settle {
            turnStarted("t1", "iroh-run-1")
            echo("c1", "t1", "iroh-run-1")
            page.add(userRow("c1", "what time is it", sentAs = "c1"), assistantRow("m1", "It is", run = "iroh-run-1"))
        }
        settle { turnStarted("t1", "run-42") }
        settle { page.replace(assistantRow("m1", "It is noon.", run = "run-42")) }
        settle { complete() }

        assertEquals(listOf("It is", " noon."), texts(events))
        assertEquals(VoiceReplyEvent.Completed, events.last())
    }

    @Test
    fun aFailedRunEndsTheReplyAsFailed() = runTest {
        val events = collect(ask("do it"))
        settle {
            turnStarted("t1", "run-1")
            echo("c1", "t1", "run-1")
            page.add(assistantRow("m1", "Working", run = "run-1"))
        }
        settle { fail() }

        assertEquals(listOf("Working"), texts(events))
        assertEquals(failed(VoiceErrorCode.RUN_FAILED), events.last())
    }

    @Test
    fun anotherClientsMessageAndReplyAreNeverSpoken() = runTest {
        val events = collect(ask("mine"))
        settle {
            turnStarted("t0", "other-run")
            page.add(userRow("u-x", "theirs"), assistantRow("m0", "Their answer.", run = "other-run"))
        }
        settle {
            echo("c1", "t1", "run-1")
            page.add(userRow("c1", "mine", sentAs = "c1"), assistantRow("m1", "Mine.", run = "run-1"))
        }
        settle { turnStarted("t1", "run-1") }
        settle { complete() }

        assertEquals(listOf("Mine."), texts(events))
        assertEquals(VoiceReplyEvent.Completed, events.last())
    }

    @Test
    fun aSendWhoseRunIsNeverPairedSpeaksNothingAndEndsUnattributed() = runTest {
        val events = collect(ask("mine"))
        settle {
            turnStarted("t0", "other-run")
            page.add(userRow("c1", "mine", sentAs = "c1"), assistantRow("m0", "Their answer.", run = "other-run"))
        }
        advanceTimeBy(31.seconds)
        runCurrent()

        assertTrue(texts(events).isEmpty())
        assertEquals(listOf<VoiceReplyEvent>(failed(VoiceErrorCode.UNATTRIBUTED)), events)
    }

    @Test
    fun stoppingTheReplyStopsItsRunThroughTheChatPage() = runTest {
        val reply = ask("tell me a story")
        val events = collect(reply)
        settle {
            turnStarted("t1", "run-1")
            echo("c1", "t1", "run-1")
            page.add(assistantRow("m1", "Once upon a time", run = "run-1"))
        }

        assertEquals(VoiceCancelOutcome.STOPPED, reply.cancel())
        assertEquals(1, page.stops)
        assertTrue(registry.sentRuns.state.value.active.isEmpty())
        assertEquals(listOf("Once upon a time"), texts(events))
    }

    private companion object {
        const val CONVERSATION = "conv-1"
        const val AGENT = "agent-1"
    }
}
