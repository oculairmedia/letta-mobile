package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecordingTest
import com.letta.mobile.util.numberOrNull
import com.letta.mobile.util.textOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The shutdown drain: a SIGTERM shutdown must not cancel in-flight turns. The host refuses new
 * turn inputs ([NodeTurnHost.isDraining]) and waits for the live ones — every one of them, up to
 * the drain's bound, cut at once by a second shutdown signal — so the App Server connection close
 * that follows cancels nothing with cause "transport". Each scenario pins the drain's telemetry
 * (`shutdown.drain.start` / `shutdown.drain.done`), the wrapper's structured evidence for an
 * operator reading the log.
 */
class NodeTurnHostDrainTest : TelemetryRecordingTest() {
    private val runtime = AppServerRuntimeScope("agent-drain", "conv-drain")
    private val agent = AgentId(runtime.agentId)
    private val conversation = ConversationId(runtime.conversationId)

    /** One scripted turn on its own host: completes when [drafts] closes, like NodeTurnHostTest's. */
    private class ScriptedTurn(val host: NodeTurnHost, val drafts: Channel<RuntimeEventPayload>)

    private fun TestScope.scriptedTurn(): ScriptedTurn {
        val host = NodeTurnHost(
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
        )
        val writer = host.journal.openTurn(agent, conversation, CLIENT_MESSAGE_ID)
        val fanout = ConversationTurnFanout(
            conversationId = runtime.conversationId,
            runtime = runtime,
            delivery = TurnFanoutDelivery(viewersFor = { emptySet() }),
            initiatorViewer = null,
            trackInitiatorFrame = writer::append,
        )
        val drafts = Channel<RuntimeEventPayload>(Channel.UNLIMITED)
        val turn = NodeTurn(
            conversationId = runtime.conversationId,
            clientMessageId = CLIENT_MESSAGE_ID,
            fanout = fanout,
            journal = writer,
        ) {
            for (payload in drafts) fanout.onStampedDraft(payload)
        }
        backgroundScope.launch { host.run(turn) }
        return ScriptedTurn(host, drafts)
    }

    private fun drainEvent(name: String): Telemetry.Event =
        assertNotNull(
            Telemetry.snapshot().lastOrNull { it.tag == "IrohNode" && it.name == name },
            "expected a $name telemetry event, saw: ${Telemetry.snapshot().map { it.name }}",
        )

    private fun Telemetry.Event.int(attr: String): Int =
        assertNotNull(attrs[attr]?.numberOrNull, "$name must carry $attr").toInt()

    private fun Telemetry.Event.text(attr: String): String =
        assertNotNull(attrs[attr]?.textOrNull, "$name must carry $attr")

    @Test
    fun shutdownWithNoLiveTurnsClosesAtOnce() = runTest {
        val host = NodeTurnHost(CoroutineScope(backgroundScope.coroutineContext + SupervisorJob()))
        var clock = 1_000L

        assertNotNull(host.beginDrain(5.minutes, nowMs = { clock }), "the first drain begins")
        assertTrue(host.isDraining, "a draining host refuses new turn inputs")
        assertNull(host.beginDrain(5.minutes, nowMs = { clock }), "a drain begins only once")

        clock += 25
        host.awaitDrained(nowMs = { clock })

        val start = drainEvent("shutdown.drain.start")
        assertEquals(0, start.int("active_turns"))
        assertEquals(300_000, start.int("bound_ms"))
        val done = drainEvent("shutdown.drain.done")
        assertEquals(0, done.int("waited_turns"))
        assertEquals(0, done.int("cut_turns"))
        assertEquals(25, done.int("elapsed_ms"))
        assertEquals("drained", done.text("reason"))
    }

    @Test
    fun shutdownRefusesInputsWhileWaitingForTheLiveTurnThenClosesCleanly() = runTest {
        val scripted = scriptedTurn()
        runCurrent()
        assertEquals(1, scripted.host.liveTurnCount)

        assertNotNull(scripted.host.beginDrain(5.minutes, nowMs = { 0 }), "the drain begins")
        val waiting = async { scripted.host.awaitDrained(nowMs = { 0 }) }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(waiting.isCompleted, "the drain waits while the turn runs")
        assertTrue(scripted.host.isDraining, "inputs stay refused for the whole drain")

        scripted.drafts.send(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed))
        scripted.drafts.close()
        // The next poll tick re-reads the count and sees the finished turn.
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0, scripted.host.liveTurnCount)

        waiting.await()
        val done = drainEvent("shutdown.drain.done")
        assertEquals(1, done.int("waited_turns"))
        assertEquals(0, done.int("cut_turns"))
        assertEquals("drained", done.text("reason"))
        assertEquals(1, drainEvent("shutdown.drain.start").int("active_turns"))
    }

    @Test
    fun shutdownEndsAtTheBoundWhenTheTurnDoesNotFinish() = runTest {
        val scripted = scriptedTurn()
        runCurrent()
        assertNotNull(scripted.host.beginDrain(250.milliseconds, nowMs = { 0 }), "the drain begins")

        val waiting = async { scripted.host.awaitDrained(nowMs = { 0 }) }
        advanceTimeBy(249)
        runCurrent()
        assertFalse(waiting.isCompleted, "the drain waits up to its bound")
        advanceTimeBy(1)
        runCurrent()

        waiting.await()
        val done = drainEvent("shutdown.drain.done")
        assertEquals(1, done.int("waited_turns"))
        assertEquals(1, done.int("cut_turns"), "the unfinished turn is cut by the bound")
        assertEquals("bound", done.text("reason"))
        assertEquals(1, scripted.host.liveTurnCount, "the cut turn keeps running; the caller closes around it")
    }

    @Test
    fun aSecondShutdownSignalCutsTheWaitImmediately() = runTest {
        val scripted = scriptedTurn()
        runCurrent()
        val cut = assertNotNull(scripted.host.beginDrain(5.minutes, nowMs = { 0 }), "the drain begins")

        val waiting = async { scripted.host.awaitDrained(nowMs = { 0 }) }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(waiting.isCompleted, "the drain would keep waiting for the live turn")

        cut()
        runCurrent()
        assertTrue(waiting.isCompleted, "the second signal ends the wait at once, not at the bound")

        val done = drainEvent("shutdown.drain.done")
        assertEquals(1, done.int("waited_turns"))
        assertEquals(1, done.int("cut_turns"))
        assertEquals("cut", done.text("reason"))
    }

    private companion object {
        const val CLIENT_MESSAGE_ID = "cm-drain-1"
    }
}
