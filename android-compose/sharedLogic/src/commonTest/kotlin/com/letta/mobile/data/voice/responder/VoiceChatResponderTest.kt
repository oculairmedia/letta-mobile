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

/** The ids the fake session hands out, in send order, and one sent by someone else. */
private object Sent {
    val FIRST = ClientMessageId("c1")
    val SECOND = ClientMessageId("c2")
    val SOMEONE_ELSE = ClientMessageId("x9")
}

/** Run ids the tests use. */
private object Runs {
    val R1 = VoiceRunId("r1")
    val R2 = VoiceRunId("r2")
    val R5 = VoiceRunId("r5")
    val UNRELATED = VoiceRunId("someone-else")
    val LIVE = VoiceRunId("iroh-run-1")
    val DURABLE = VoiceRunId("run-42")
}

/** Row keys the tests use. */
private object Keys {
    val U1 = VoiceRowKey("u1")
    val OTHER = VoiceRowKey("other")
    val THINK = VoiceRowKey("think")
    val E1 = VoiceRowKey("e1")
    val M0 = VoiceRowKey("m0")
    val M1 = VoiceRowKey("m1")
    val M2 = VoiceRowKey("m2")
    val M3 = VoiceRowKey("m3")
    val M5 = VoiceRowKey("m5")
}

/** What the person says. */
private object Say {
    val ASK = VoiceText("what time is it")
    val FIRST = VoiceText("first")
    val SECOND = VoiceText("second")
}

/** A voice chat session the test scripts: sends get ids c1, c2, ...; stops are recorded. */
private class FakeVoiceChatSession : VoiceChatSession {
    private val mutableState = MutableStateFlow(VoiceChatState(conversation = VoiceConversationKey("a1")))
    val sent = mutableListOf<VoiceText>()
    var stops = 0
        private set
    var endRunOnStop = true

    override val state: StateFlow<VoiceChatState> = mutableState

    override suspend fun send(text: VoiceText): SentUtterance {
        sent += text
        return SentUtterance(ClientMessageId("c${sent.size}"))
    }

    override fun stopRun() {
        stops++
        if (endRunOnStop) update { copy(activeRuns = emptySet()) }
    }

    fun update(change: VoiceChatState.() -> VoiceChatState) = mutableState.update(change)

    fun add(vararg rows: VoiceChatRow) = update { copy(rows = this.rows + rows) }

    fun replace(row: VoiceChatRow) = update { copy(rows = rows.map { if (it.key == row.key) row else it }) }

    /** Publishes that sent message [id] started the run known as [run] (and [alias]), now in flight. */
    fun start(id: ClientMessageId, run: VoiceRunId, alias: VoiceRunId? = null) = update {
        val runs = setOfNotNull(run, alias)
        copy(startedRuns = startedRuns + (id to runs), activeRuns = activeRuns + runs)
    }

    fun end(run: VoiceRunId, other: VoiceRunId? = null) = update { copy(activeRuns = activeRuns - setOfNotNull(run, other)) }

    fun failRun(run: VoiceRunId) = update { copy(failedRuns = setOf(run), activeRuns = activeRuns - run) }

    fun moveTo(conversation: VoiceConversationKey) = update { copy(conversation = conversation) }
}

private fun user(key: VoiceRowKey, sentAs: ClientMessageId? = null) = VoiceChatRow(key, VoiceRowKind.USER, Say.ASK, sentAs)

private fun reply(key: VoiceRowKey, words: VoiceText, run: VoiceRunId) =
    VoiceChatRow(key, VoiceRowKind.ASSISTANT_TEXT, words, runId = run)

private fun marker(key: VoiceRowKey, kind: VoiceRowKind, run: VoiceRunId) = VoiceChatRow(key, kind, VoiceText("x"), runId = run)

/** One way a started run can end, and what the reply reports for it. */
private data class Ending(val apply: (FakeVoiceChatSession) -> Unit, val expected: VoiceReplyEvent)

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChatResponderTest {
    private val session = FakeVoiceChatSession()
    private val responder = VoiceChatResponder(session)

    private fun ask(words: VoiceText = Say.ASK): VoiceReply = responder.respond(VoiceUtterance(words))

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
        assertEquals(listOf(Say.ASK), session.sent)
        settle { add(user(Keys.U1, Sent.FIRST)); start(Sent.FIRST, Runs.R1); add(reply(Keys.M1, VoiceText("It is"), Runs.R1)) }
        settle { replace(reply(Keys.M1, VoiceText("It is noon."), Runs.R1)) }
        settle { end(Runs.R1) }
        assertEquals(listOf("It is", " noon."), texts(events))
        assertEquals(VoiceReplyEvent.Completed, events.last())
    }

    @Test
    fun onlyAssistantTextOfItsRunIsSpoken() = runTest {
        val events = collect(ask())
        settle {
            start(Sent.FIRST, Runs.R1)
            add(marker(Keys.THINK, VoiceRowKind.OTHER, Runs.R1), reply(Keys.M0, VoiceText("Someone else's reply."), Runs.R5))
            add(reply(Keys.M1, VoiceText("Found it."), Runs.R1))
        }
        settle { end(Runs.R1) }
        assertEquals(listOf("Found it."), texts(events))
    }

    @Test
    fun successiveMessagesOfTheRunAreSeparated() = runTest {
        val events = collect(ask())
        settle {
            start(Sent.FIRST, Runs.R1)
            add(reply(Keys.M1, VoiceText("First."), Runs.R1), reply(Keys.M2, VoiceText("Second."), Runs.R1))
        }
        settle { end(Runs.R1) }
        assertEquals(listOf("First.\nSecond."), texts(events))
    }

    @Test
    fun runEndings() = runTest {
        val cases = listOf(
            Ending({ it.end(Runs.R1) }, VoiceReplyEvent.Completed),
            Ending({ it.failRun(Runs.R1) }, failed(VoiceErrorCode.RUN_FAILED)),
            Ending({ it.add(marker(Keys.E1, VoiceRowKind.ERROR, Runs.R1)); it.end(Runs.R1) }, failed(VoiceErrorCode.RUN_FAILED)),
            Ending({ it.moveTo(VoiceConversationKey("a2")) }, failed(VoiceErrorCode.CANCELLED)),
        )
        cases.forEachIndexed { index, ending ->
            val local = FakeVoiceChatSession()
            val events = mutableListOf<VoiceReplyEvent>()
            val reply = VoiceChatResponder(local).respond(VoiceUtterance(Say.ASK))
            backgroundScope.launch { reply.events.collect { events += it } }
            runCurrent()
            local.start(Sent.FIRST, Runs.R1)
            runCurrent()
            ending.apply(local)
            runCurrent()
            assertEquals(listOf(ending.expected), events, "case $index")
        }
    }

    @Test
    fun sameTextRowsInSeparateUpdatesNeverSpeakTheOtherReply() = runTest {
        val events = collect(ask())
        settle { add(user(Keys.OTHER), reply(Keys.M5, VoiceText("Their answer."), Runs.R5)); start(Sent.SOMEONE_ELSE, Runs.R5) }
        settle { add(user(Keys.U1, Sent.FIRST)); start(Sent.FIRST, Runs.R2); add(reply(Keys.M3, VoiceText("Answer two."), Runs.R2)) }
        settle { end(Runs.R5, Runs.R2) }
        assertEquals(listOf("Answer two."), texts(events))
    }

    @Test
    fun speechStartsOnlyOnceTheSessionPublishesTheRun() = runTest {
        val events = collect(ask())
        settle {
            add(user(Keys.U1, Sent.FIRST), reply(Keys.M3, VoiceText("Answer."), Runs.R2))
            update { copy(activeRuns = setOf(Runs.R2)) }
        }
        assertEquals(emptyList(), texts(events))
        settle { start(Sent.FIRST, Runs.R2) }
        assertEquals(listOf("Answer."), texts(events))
    }

    @Test
    fun textUnderTheProvisionalOrTheDurableRunIdIsOurs() = runTest {
        val events = collect(ask())
        settle { start(Sent.FIRST, Runs.LIVE, Runs.DURABLE); add(reply(Keys.M1, VoiceText("Live part."), Runs.LIVE)) }
        settle { add(reply(Keys.M2, VoiceText("Durable part."), Runs.DURABLE)) }
        settle { end(Runs.LIVE, Runs.DURABLE) }
        assertEquals(listOf("Live part.", "\nDurable part."), texts(events))
    }

    @Test
    fun identityNeverPublishedFailsUnattributedSilently() = runTest {
        val events = collect(ask())
        settle {
            add(user(Keys.U1, Sent.FIRST), reply(Keys.M1, VoiceText("Some answer."), Runs.R1))
            update { copy(activeRuns = setOf(Runs.R1)) }
        }
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
        val first = collect(ask(Say.FIRST))
        settle { start(Sent.FIRST, Runs.R1); add(reply(Keys.M1, VoiceText("Partial"), Runs.R1)) }
        val second = collect(ask(Say.SECOND))
        assertEquals(1, session.stops)
        assertEquals(listOf(Say.FIRST, Say.SECOND), session.sent)
        settle {
            add(reply(Keys.M2, VoiceText("Late words from the first run"), Runs.R1))
            start(Sent.SECOND, Runs.R2)
            add(reply(Keys.M3, VoiceText("Answer two."), Runs.R2))
        }
        settle { end(Runs.R2) }
        assertEquals(listOf("Partial"), texts(first))
        assertEquals(listOf("Answer two."), texts(second))
    }

    @Test
    fun cancelStopsOnlyItsOwnRunningRun() = runTest {
        assertEquals(VoiceCancelOutcome.ALREADY_FINISHED, ask().cancel())
        assertEquals(emptyList<VoiceText>(), session.sent)
        session.update { copy(activeRuns = setOf(Runs.UNRELATED)) }
        val notYetRunning = ask()
        collect(notYetRunning)
        assertEquals(VoiceCancelOutcome.ALREADY_FINISHED, notYetRunning.cancel())
        assertEquals(0, session.stops)
        val running = ask()
        collect(running)
        settle { start(Sent.SECOND, Runs.R2) }
        session.endRunOnStop = false
        val outcome = backgroundScope.launch { assertEquals(VoiceCancelOutcome.UNRESPONSIVE, running.cancel()) }
        advanceTimeBy(ChatReplyPolicy().stopTimeout + 1.seconds)
        runCurrent()
        assertEquals(true, outcome.isCompleted)
        assertEquals(1, session.stops)
    }

    @Test
    fun replyCancelledWhileThePreviousRunStopsSendsNothing() = runTest {
        collect(ask(Say.FIRST))
        settle { start(Sent.FIRST, Runs.R1) }
        session.endRunOnStop = false
        val second = ask(Say.SECOND)
        collect(second)
        backgroundScope.launch { second.cancel() }
        advanceTimeBy(ChatReplyPolicy().stopTimeout + 1.seconds)
        runCurrent()
        assertEquals(listOf(Say.FIRST), session.sent)
    }
}
