package com.letta.mobile.data.voice.responder

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
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

/** A voice chat session the test scripts: sends get ids c1, c2, ...; stops are recorded. */
private class FakeVoiceChatSession : VoiceChatSession {
    private val mutableState = MutableStateFlow(VoiceChatState(conversation = VoiceConversationKey("a1")))
    val sent = mutableListOf<String>()
    var stops = 0
        private set
    var endRunOnStop = true

    override val state: StateFlow<VoiceChatState> = mutableState

    override suspend fun send(text: VoiceText): SentUtterance {
        sent += text.value
        return SentUtterance(ClientMessageId("c${sent.size}"))
    }

    override fun stopRun() {
        stops++
        if (endRunOnStop) update { copy(activeRuns = emptySet()) }
    }

    fun update(change: VoiceChatState.() -> VoiceChatState) = mutableState.update(change)

    fun add(vararg rows: VoiceChatRow) = update { copy(rows = this.rows + rows) }

    fun replace(row: VoiceChatRow) = update { copy(rows = rows.map { if (it.key == row.key) row else it }) }

    /** Publishes that sent message [id] started the run with [runIds], now in flight. */
    fun start(id: String, vararg runIds: String) = update {
        val runs = runIds.map(::VoiceRunId).toSet()
        copy(startedRuns = startedRuns + (ClientMessageId(id) to runs), activeRuns = activeRuns + runs)
    }

    fun end(vararg runIds: String) = update { copy(activeRuns = activeRuns - runIds.map(::VoiceRunId).toSet()) }

    fun failRun(run: String) = update { copy(failedRuns = setOf(VoiceRunId(run)), activeRuns = activeRuns - VoiceRunId(run)) }
}

private fun user(key: String, sentAs: String? = null) =
    VoiceChatRow(VoiceRowKey(key), VoiceRowKind.USER, VoiceText("what time is it"), sentAs?.let(::ClientMessageId))

private fun assistant(key: String, words: String, run: String) =
    VoiceChatRow(VoiceRowKey(key), VoiceRowKind.ASSISTANT_TEXT, VoiceText(words), runId = VoiceRunId(run))

private fun row(key: String, kind: VoiceRowKind, run: String) =
    VoiceChatRow(VoiceRowKey(key), kind, VoiceText("x"), runId = VoiceRunId(run))

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChatResponderTest {
    private val session = FakeVoiceChatSession()
    private val responder = VoiceChatResponder(session)

    private fun ask(words: VoiceText = VoiceText("what time is it")): VoiceReply = responder.respond(VoiceUtterance(words))

    private fun TestScope.collect(reply: VoiceReply): MutableList<VoiceReplyEvent> {
        val events = mutableListOf<VoiceReplyEvent>()
        backgroundScope.launch { reply.events.collect { events += it } }
        runCurrent()
        return events
    }

    private fun TestScope.settle(change: FakeVoiceChatSession.() -> Unit) {
        session.change()
        runCurrent()
    }

    private fun texts(events: List<VoiceReplyEvent>) = events.filterIsInstance<VoiceReplyEvent.Text>().map { it.delta.value }

    private fun failed(code: VoiceErrorCode) = VoiceReplyEvent.Failed(VoiceError(code, VoicePort.RESPONDER))

    @Test
    fun followsItsOwnRunAsItStreams() = runTest {
        val events = collect(ask())
        assertEquals(listOf("what time is it"), session.sent)
        settle { add(user("u1", "c1")); start("c1", "r1"); add(assistant("m1", "It is", "r1")) }
        settle { replace(assistant("m1", "It is noon.", "r1")) }
        settle { end("r1") }
        assertEquals(listOf("It is", " noon."), texts(events))
        assertEquals(VoiceReplyEvent.Completed, events.last())
    }

    @Test
    fun onlyAssistantTextOfItsRunIsSpoken() = runTest {
        val events = collect(ask())
        settle {
            start("c1", "r1")
            add(row("think", VoiceRowKind.OTHER, "r1"), assistant("m0", "Someone else's reply.", "r0"), assistant("m1", "Found it.", "r1"))
        }
        settle { end("r1") }
        assertEquals(listOf("Found it."), texts(events))
    }

    @Test
    fun successiveMessagesOfTheRunAreSeparated() = runTest {
        val events = collect(ask())
        settle { start("c1", "r1"); add(assistant("m1", "First.", "r1"), assistant("m2", "Second.", "r1")) }
        settle { end("r1") }
        assertEquals(listOf("First.\nSecond."), texts(events))
    }

    @Test
    fun runEndings() = runTest {
        val cases = listOf<Pair<(FakeVoiceChatSession) -> Unit, VoiceReplyEvent>>(
            { s: FakeVoiceChatSession -> s.end("r1") } to VoiceReplyEvent.Completed,
            { s: FakeVoiceChatSession -> s.failRun("r1") } to failed(VoiceErrorCode.RUN_FAILED),
            { s: FakeVoiceChatSession -> s.add(row("e1", VoiceRowKind.ERROR, "r1")); s.end("r1") } to failed(VoiceErrorCode.RUN_FAILED),
            { s: FakeVoiceChatSession -> s.update { copy(conversation = VoiceConversationKey("a2")) } } to failed(VoiceErrorCode.CANCELLED),
        )
        cases.forEachIndexed { index, (ending, expected) ->
            val local = FakeVoiceChatSession()
            val events = mutableListOf<VoiceReplyEvent>()
            val reply = VoiceChatResponder(local).respond(VoiceUtterance(VoiceText("hi")))
            backgroundScope.launch { reply.events.collect { events += it } }
            runCurrent()
            local.start("c1", "r1")
            runCurrent()
            ending(local)
            runCurrent()
            assertEquals(listOf(expected), events, "case $index")
        }
    }

    @Test
    fun sameTextRowsInSeparateUpdatesNeverSpeakTheOtherReply() = runTest {
        val events = collect(ask())
        settle { add(user("other"), assistant("m5", "Their answer.", "r5")); start("x9", "r5") }
        settle { add(user("u1", "c1")); start("c1", "r2"); add(assistant("m3", "Answer two.", "r2")) }
        settle { end("r5", "r2") }
        assertEquals(listOf("Answer two."), texts(events))
    }

    @Test
    fun speechStartsOnlyOnceTheSessionPublishesTheRun() = runTest {
        val events = collect(ask())
        settle { add(user("u1", "c1"), assistant("m3", "Answer.", "r2")); update { copy(activeRuns = setOf(VoiceRunId("r2"))) } }
        assertEquals(emptyList(), texts(events))
        settle { start("c1", "r2") }
        assertEquals(listOf("Answer."), texts(events))
    }

    @Test
    fun textUnderTheProvisionalOrTheDurableRunIdIsOurs() = runTest {
        val events = collect(ask())
        settle { start("c1", "iroh-run-1", "run-42"); add(assistant("m1", "Live part.", "iroh-run-1")) }
        settle { add(assistant("m2", "Durable part.", "run-42")) }
        settle { end("iroh-run-1", "run-42") }
        assertEquals(listOf("Live part.", "\nDurable part."), texts(events))
    }

    @Test
    fun identityNeverPublishedFailsUnattributedSilently() = runTest {
        val events = collect(ask())
        settle { add(user("u1", "c1"), assistant("m1", "Some answer.", "r1")); update { copy(activeRuns = setOf(VoiceRunId("r1"))) } }
        advanceTimeBy(ChatReplyPolicy().deliveryTimeout + 1.seconds)
        runCurrent()
        assertEquals(listOf<VoiceReplyEvent>(failed(VoiceErrorCode.UNATTRIBUTED)), events)
    }

    @Test
    fun messageThatNeverAppearsIsNotDelivered() = runTest {
        val events = collect(ask())
        advanceTimeBy(ChatReplyPolicy().deliveryTimeout + 1.seconds)
        runCurrent()
        assertEquals(listOf<VoiceReplyEvent>(failed(VoiceErrorCode.NOT_DELIVERED)), events)
    }

    @Test
    fun secondUtteranceStopsOnlyTheFirstRunBeforeSending() = runTest {
        val first = collect(ask(VoiceText("first")))
        settle { start("c1", "r1"); add(assistant("m1", "Partial", "r1")) }
        val second = collect(ask(VoiceText("second")))
        assertEquals(1, session.stops)
        assertEquals(listOf("first", "second"), session.sent)
        settle { add(assistant("m2", "Late words from the first run", "r1")); start("c2", "r2"); add(assistant("m3", "Answer two.", "r2")) }
        settle { end("r2") }
        assertEquals(listOf("Partial"), texts(first))
        assertEquals(listOf("Answer two."), texts(second))
    }

    @Test
    fun cancelStopsOnlyItsOwnRunningRun() = runTest {
        assertEquals(VoiceCancelOutcome.ALREADY_FINISHED, ask().cancel())
        assertEquals(emptyList<String>(), session.sent)
        session.update { copy(activeRuns = setOf(VoiceRunId("someone-else"))) }
        val notYetRunning = ask()
        collect(notYetRunning)
        assertEquals(VoiceCancelOutcome.ALREADY_FINISHED, notYetRunning.cancel())
        assertEquals(0, session.stops)
        val running = ask()
        collect(running)
        settle { start("c2", "r3") }
        session.endRunOnStop = false
        val outcome = backgroundScope.launch { assertEquals(VoiceCancelOutcome.UNRESPONSIVE, running.cancel()) }
        advanceTimeBy(ChatReplyPolicy().stopTimeout + 1.seconds)
        runCurrent()
        assertEquals(true, outcome.isCompleted)
        assertEquals(1, session.stops)
    }

    @Test
    fun replyCancelledWhileThePreviousRunStopsSendsNothing() = runTest {
        collect(ask(VoiceText("first")))
        settle { start("c1", "r1") }
        session.endRunOnStop = false
        val second = ask(VoiceText("second"))
        collect(second)
        backgroundScope.launch { second.cancel() }
        advanceTimeBy(ChatReplyPolicy().stopTimeout + 1.seconds)
        runCurrent()
        assertEquals(listOf("first"), session.sent)
    }
}
