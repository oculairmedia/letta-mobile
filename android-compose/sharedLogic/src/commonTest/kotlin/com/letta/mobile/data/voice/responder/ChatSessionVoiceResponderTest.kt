package com.letta.mobile.data.voice.responder

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.FakeChatSessionPort
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** A chat owner whose timeline the test scripts; sends and stops are recorded. */
private class ScriptedChatSession : ChatSessionPort {
    private val base = FakeChatSessionPort()
    private val ui = MutableStateFlow(ChatUiState(agentId = "a1"))
    val sent = mutableListOf<String>()
    var stops = 0
        private set
    var endRunOnStop = true

    override val uiState: StateFlow<ChatUiState> = ui
    override val composer: StateFlow<ChatComposerUiState> = base.composer
    override val actions: ChatActions = object : ChatActions by base.actions {
        override fun sendText(text: String) {
            sent += text
        }

        override fun stopRun() {
            stops++
            if (endRunOnStop) update { copy(isAgentTyping = false, isStreaming = false) }
        }
    }

    fun update(change: ChatUiState.() -> ChatUiState) = ui.update(change)

    fun append(vararg added: UiMessage) = update { copy(messages = (messages + added).toPersistentList()) }

    fun replace(message: UiMessage) = update {
        copy(messages = messages.map { if (it.id == message.id) message else it }.toPersistentList())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionVoiceResponderTest {
    private val session = ScriptedChatSession()
    private val responder = ChatSessionVoiceResponder(session)

    private val userRow = UiMessage(id = "", role = "user", content = "spoken", timestamp = "t")

    private val assistantRow = UiMessage(id = "", role = "assistant", content = "", timestamp = "t", runId = "r1")

    private fun TestScope.collect(reply: VoiceReply): MutableList<VoiceReplyEvent> {
        val events = mutableListOf<VoiceReplyEvent>()
        backgroundScope.launch { reply.events.collect { events += it } }
        runCurrent()
        return events
    }

    private fun ask(words: VoiceText = VoiceText("what time is it")): VoiceReply = responder.respond(VoiceUtterance(words))

    private fun TestScope.startRun() {
        session.append(userRow.copy(id = "u1"))
        session.update { copy(isAgentTyping = true) }
        runCurrent()
    }

    private fun TestScope.endRun() {
        session.update { copy(isAgentTyping = false, isStreaming = false) }
        runCurrent()
    }

    private fun texts(events: List<VoiceReplyEvent>) = events.filterIsInstance<VoiceReplyEvent.Text>().map { it.delta.value }

    @Test
    fun followsOneAssistantMessageAsItStreams() = runTest {
        val events = collect(ask())
        assertEquals(listOf("what time is it"), session.sent)
        startRun()
        session.append(assistantRow.copy(id = "m1", content = "It is"))
        runCurrent()
        session.replace(assistantRow.copy(id = "m1", content = "It is noon."))
        runCurrent()
        endRun()
        assertEquals(listOf("It is", " noon."), texts(events))
        assertEquals(VoiceReplyEvent.Completed, events.last())
    }

    @Test
    fun ignoresReasoningToolRowsAndOlderMessages() = runTest {
        session.append(assistantRow.copy(id = "old", content = "Earlier answer.", runId = "r0"))
        val events = collect(ask())
        startRun()
        session.append(
            assistantRow.copy(id = "think", content = "pondering", isReasoning = true),
            assistantRow.copy(id = "tool", toolCalls = listOf(UiToolCall(name = "search", arguments = "{}", result = null))),
            assistantRow.copy(id = "m1", content = "Found it."),
        )
        runCurrent()
        endRun()
        assertEquals(listOf("Found it."), texts(events))
    }

    @Test
    fun separatesSuccessiveAssistantMessages() = runTest {
        val events = collect(ask())
        startRun()
        session.append(assistantRow.copy(id = "m1", content = "First."), assistantRow.copy(id = "m2", content = "Second."))
        runCurrent()
        endRun()
        assertEquals(listOf("First.\nSecond."), texts(events))
    }

    @Test
    fun runWithoutAssistantTextCompletesWithNothing() = runTest {
        val events = collect(ask())
        startRun()
        endRun()
        assertEquals(listOf<VoiceReplyEvent>(VoiceReplyEvent.Completed), events)
    }

    @Test
    fun failedRunReportsRunFailed() = runTest {
        val events = collect(ask())
        startRun()
        session.update { copy(isAgentTyping = false, runFailed = true) }
        runCurrent()
        assertEquals(VoiceReplyEvent.Failed(VoiceError(VoiceErrorCode.RUN_FAILED, VoicePort.RESPONDER)), events.single())
    }

    @Test
    fun replyThatNeverStartsIsNotDelivered() = runTest {
        val events = collect(ask())
        advanceTimeBy(ChatReplyPolicy().deliveryTimeout + 1.seconds)
        runCurrent()
        assertEquals(VoiceReplyEvent.Failed(VoiceError(VoiceErrorCode.NOT_DELIVERED, VoicePort.RESPONDER)), events.single())
    }

    @Test
    fun agentChangeEndsTheReplyAsCancelled() = runTest {
        val events = collect(ask())
        startRun()
        session.update { copy(agentId = "a2") }
        runCurrent()
        assertEquals(VoiceReplyEvent.Failed(VoiceError(VoiceErrorCode.CANCELLED, VoicePort.RESPONDER)), events.single())
    }

    @Test
    fun secondUtteranceStopsTheFirstRunBeforeSending() = runTest {
        val first = collect(ask(VoiceText("first")))
        startRun()
        session.append(assistantRow.copy(id = "m1", content = "Partial"))
        runCurrent()
        val second = collect(ask(VoiceText("second")))
        assertEquals(1, session.stops)
        assertEquals(listOf("first", "second"), session.sent)
        session.append(assistantRow.copy(id = "m2", content = "Late words from the first run", runId = "r1"))
        session.append(userRow.copy(id = "u2"), assistantRow.copy(id = "m3", content = "Answer two.", runId = "r2"))
        runCurrent()
        endRun()
        assertEquals(listOf("Partial"), texts(first))
        assertEquals(listOf("Answer two."), texts(second))
    }

    @Test
    fun cancelOutcomes() = runTest {
        assertEquals(VoiceCancelOutcome.ALREADY_FINISHED, ask().cancel())
        assertEquals(emptyList<String>(), session.sent)
        val running = ask()
        collect(running)
        startRun()
        session.endRunOnStop = false
        val outcome = backgroundScope.launch { assertEquals(VoiceCancelOutcome.UNRESPONSIVE, running.cancel()) }
        advanceTimeBy(ChatReplyPolicy().stopTimeout + 1.seconds)
        runCurrent()
        assertEquals(true, outcome.isCompleted)
        session.update { copy(isAgentTyping = false) }
        val stopped = ask()
        collect(stopped)
        session.endRunOnStop = true
        session.append(userRow.copy(id = "u9", isPending = true))
        session.update { copy(isAgentTyping = true) }
        runCurrent()
        val stopsBefore = session.stops
        assertEquals(VoiceCancelOutcome.STOPPED, stopped.cancel())
        assertEquals(stopsBefore + 1, session.stops)
    }

    @Test
    fun olderRunEndingDoesNotCompleteAQueuedUtterance() = runTest {
        session.update { copy(isAgentTyping = true) }
        val events = collect(ask())
        session.append(userRow.copy(id = "u2", isPending = true))
        runCurrent()
        endRun()
        assertEquals(emptyList<VoiceReplyEvent>(), events)
        session.update { copy(isAgentTyping = true) }
        session.append(assistantRow.copy(id = "m3", content = "Answer two.", runId = "r2"))
        runCurrent()
        endRun()
        assertEquals(listOf("Answer two."), texts(events))
        assertEquals(VoiceReplyEvent.Completed, events.last())
    }

    @Test
    fun lateRowOfThePreviousRunAfterTheNewUtteranceIsNotSpoken() = runTest {
        collect(ask(VoiceText("first")))
        startRun()
        session.append(assistantRow.copy(id = "m1", content = "Partial"))
        runCurrent()
        val second = collect(ask(VoiceText("second")))
        session.append(userRow.copy(id = "u2"))
        session.append(assistantRow.copy(id = "m2", content = "Late words from the first run", runId = "r1"))
        session.append(assistantRow.copy(id = "m3", content = "Answer two.", runId = "r2"))
        runCurrent()
        endRun()
        assertEquals(listOf("Answer two."), texts(second))
    }

    @Test
    fun pendingRowConfirmationKeepsTheAnchor() = runTest {
        val events = collect(ask())
        session.append(userRow.copy(id = "otid1", clientMessageId = "otid1", isPending = true))
        session.update { copy(isAgentTyping = true) }
        runCurrent()
        val confirmed = userRow.copy(id = "srv1", clientMessageId = "otid1")
        session.update { copy(messages = messages.map { if (it.id == "otid1") confirmed else it }.toPersistentList()) }
        session.append(assistantRow.copy(id = "m1", content = "Hi."))
        runCurrent()
        endRun()
        assertEquals(listOf("Hi."), texts(events))
    }

    @Test
    fun replyCancelledWhileThePreviousRunStopsSendsNothing() = runTest {
        collect(ask(VoiceText("first")))
        startRun()
        session.endRunOnStop = false
        val second = ask(VoiceText("second"))
        collect(second)
        backgroundScope.launch { second.cancel() }
        advanceTimeBy(ChatReplyPolicy().stopTimeout + 1.seconds)
        runCurrent()
        assertEquals(listOf("first"), session.sent)
    }

    @Test
    fun runThatEndedBeforeDeliveryWasObservedCompletes() = runTest {
        val events = collect(ask())
        session.append(userRow.copy(id = "u1"))
        runCurrent()
        assertEquals(listOf<VoiceReplyEvent>(VoiceReplyEvent.Completed), events)
    }
}
