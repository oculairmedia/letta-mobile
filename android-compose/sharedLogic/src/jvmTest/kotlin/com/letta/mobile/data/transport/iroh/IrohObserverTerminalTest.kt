package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class IrohObserverTerminalTest : IrohObserverFixture() {
    @Test
    fun passiveObserverTurnFinishedEmitsTurnDoneForAutonomousTurn() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val ingestor = observerIngestor(IrohTurnRegistry(), emitted)

        val terminal = TerminalFrameSpec("agent-1", "conv-auto", 2L, "turn-auto-1", "run-auto-1")
        ingestor.ingestObserverFrame(ObserverFrameRequest(observedAssistant("auto-1", 1L), 1L))
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(turnFinishedFrame(terminal), 1L),
        )

        assertAutonomousCompletion(emitted, terminal)
    }

    @Test
    fun passiveObserverStopReasonFollowedByTurnFinishedEmitsSingleTurnDoneWithTurnId() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val ingestor = observerIngestor(IrohTurnRegistry(), emitted)

        val delta = """
            {"message_type":"assistant_message","id":"m1","logical_message_id":"lm-m1",
            "turn_id":"turn-auto-2","run_id":"run-auto-2","text_seq":1,"content":"Done."}
        """.trimIndent()
        val stop = """{"message_type":"stop_reason","stop_reason":"end_turn","run_id":"run-auto-2"}"""
        ingestor.ingestObserverFrame(ObserverFrameRequest(streamDelta("agent-1", "conv-auto", 1L, delta), 1L))
        ingestor.ingestObserverFrame(ObserverFrameRequest(streamDelta("agent-1", "conv-auto", 2L, stop), 1L))
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(turnFinishedFrame(TerminalFrameSpec("agent-1", "conv-auto", 3L, "turn-auto-2", "run-auto-2")), 1L),
        )

        assertEquals(listOf("AssistantMessage", "StopReason", "TurnDone"), emitted.map { it::class.simpleName })
        val stopFrame = assertIs<ServerFrame.StopReason>(emitted[1])
        assertEquals("turn-auto-2", stopFrame.turnId)
        assertEquals("run-auto-2", stopFrame.runId)
        val done = assertIs<ServerFrame.TurnDone>(emitted[2])
        assertEquals("turn-auto-2", done.turnId)
        assertEquals("run-auto-2", done.runId)
        assertEquals("completed", done.status)
    }

    @Test
    fun passiveObserverIdleLoopStatusAfterEvidenceCompletesAutonomousTurnOnce() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val ingestor = observerIngestor(IrohTurnRegistry(), emitted)

        val terminal = TerminalFrameSpec("agent-1", "conv-auto", 3L, "turn-auto-3", "run-auto-3")
        ingestor.ingestObserverFrame(ObserverFrameRequest(observedAssistant("auto-3", 1L), 1L))
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(loopStatusFrame(LoopFrameSpec("agent-1", "conv-auto", 2L, "WAITING_ON_INPUT")), 1L),
        )
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(turnFinishedFrame(terminal), 1L),
        )

        assertAutonomousCompletion(emitted, terminal)
    }

    @Test
    fun retiredEngineTurnFinishedIsSkippedByObserver() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val registry = IrohTurnRegistry()
        val turn = engineOwnedTurn(registry)
        val publication = IrohTerminalPublication(turn, IrohTerminalStatus("completed"), IrohTerminalSource.Engine)
        assertTrue(registry.claimTerminal(publication))
        assertTrue(registry.retireClaimed(publication))

        val ingestor = observerIngestor(registry, emitted)
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(turnFinishedFrame(TerminalFrameSpec("agent-1", "conv-1", 1L, "turn-1", "local-run-55")), 1L),
        )

        assertTrue(emitted.isEmpty(), "Retired local turn's turn_finished must not re-emit TurnDone")
    }

    @Test
    fun unobservedTerminalDoesNotEmitUnscopedTurnDone() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val ingestor = observerIngestor(IrohTurnRegistry(), emitted)
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(turnFinishedFrame(TerminalFrameSpec("agent-1", "conv-foreign", 1L, "foreign-turn", "foreign-run")), 1L),
        )
        assertTrue(emitted.isEmpty(), "A terminal without observer evidence cannot be assigned to a local send")
    }

    @Test
    fun oldTerminalCannotRetireCurrentEngineTurn() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val registry = IrohTurnRegistry()
        val current = engineOwnedTurn(registry)
        val ingestor = observerIngestor(registry, emitted)
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(turnFinishedFrame(TerminalFrameSpec("agent-1", "conv-1", 1L, "old-turn", "old-run")), 1L),
        )
        advanceTimeBy(IrohObserverIngestor.OBSERVER_TERMINAL_GRACE_MS + 1)
        runCurrent()
        assertFalse(current.hasTerminal, "The old run must not claim the current engine turn")
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun duplicateTerminalPreservesNewerPassiveTurnForIdleCompletion() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val ingestor = observerIngestor(IrohTurnRegistry(), emitted)
        suspend fun ingest(frame: AppServerReceivedFrame) = ingestor.ingestObserverFrame(ObserverFrameRequest(frame, 1L))
        ingest(observedAssistant("old", 1L))
        ingest(turnFinishedFrame(TerminalFrameSpec("agent-1", "conv-auto", 2L, "turn-old", "run-old")))
        ingest(observedAssistant("new", 3L))
        ingest(turnFinishedFrame(TerminalFrameSpec("agent-1", "conv-auto", 4L, "turn-old", "run-old")))
        ingest(loopStatusFrame(LoopFrameSpec("agent-1", "conv-auto", 5L, "WAITING_ON_INPUT")))
        assertEquals(listOf("turn-old", "turn-new"), emitted.filterIsInstance<ServerFrame.TurnDone>().map { it.turnId })
    }

    @Test
    fun envelopeIdentitySurvivesIdleCompletionAndAuthoritativeTerminal() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val ingestor = observerIngestor(IrohTurnRegistry(), emitted)
        val frame = observedAssistant("envelope", 1L)
        val delta = (frame.frame as AppServerInboundFrame.StreamDelta).delta as JsonObject
        val raw = JsonObject(frame.raw + mapOf(
            "turn_id" to delta.getValue("turn_id"),
            "run_id" to delta.getValue("run_id"),
            "delta" to JsonObject(delta - "turn_id" - "run_id"),
        ))
        ingestor.ingestObserverFrame(ObserverFrameRequest(AppServerProtocol.decodeFrame(raw.toString(), AppServerChannel.Stream), 1L))
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(loopStatusFrame(LoopFrameSpec("agent-1", "conv-auto", 2L, "WAITING_ON_INPUT")), 1L),
        )
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(turnFinishedFrame(TerminalFrameSpec("agent-1", "conv-auto", 3L, "turn-envelope", "run-envelope")), 1L),
        )
        val done = emitted.filterIsInstance<ServerFrame.TurnDone>().single()
        assertEquals("turn-envelope", done.turnId)
        assertEquals("run-envelope", done.runId)
    }

    @Test
    fun currentEngineRunTerminalStillUsesTheGraceFallback() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val registry = IrohTurnRegistry()
        val current = engineOwnedTurn(registry)
        val ingestor = observerIngestor(registry, emitted)
        // Server and local transport turn IDs differ; the promoted run establishes ownership.
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(turnFinishedFrame(TerminalFrameSpec("agent-1", "conv-1", 1L, "server-turn", current.runId)), 1L),
        )
        assertFalse(current.hasTerminal)
        advanceTimeBy(IrohObserverIngestor.OBSERVER_TERMINAL_GRACE_MS + 1)
        runCurrent()
        assertTrue(current.hasTerminal)
        assertEquals(current.turnId, emitted.filterIsInstance<ServerFrame.TurnDone>().single().turnId)
    }

    @Test
    fun retiredEngineTerminalPreservesNewerPassiveTurn() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val registry = IrohTurnRegistry()
        val old = engineOwnedTurn(registry)
        val publication = IrohTerminalPublication(old, IrohTerminalStatus("completed"), IrohTerminalSource.Engine)
        assertTrue(registry.claimTerminal(publication))
        assertTrue(registry.retireClaimed(publication))
        val ingestor = observerIngestor(registry, emitted)
        ingestor.ingestObserverFrame(ObserverFrameRequest(observedAssistant("new", 1L, "conv-1"), 1L))
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(turnFinishedFrame(TerminalFrameSpec("agent-1", "conv-1", 2L, old.turnId, old.runId)), 1L),
        )
        ingestor.ingestObserverFrame(ObserverFrameRequest(loopStatusFrame(LoopFrameSpec("agent-1", "conv-1", 3L, "WAITING_ON_INPUT")), 1L))
        assertEquals("turn-new", emitted.filterIsInstance<ServerFrame.TurnDone>().single().turnId)
    }

    private fun assertAutonomousCompletion(emitted: List<ServerFrame>, expected: TerminalFrameSpec) {
        assertEquals(listOf("AssistantMessage", "TurnDone"), emitted.map { it::class.simpleName })
        val done = assertIs<ServerFrame.TurnDone>(emitted.last())
        assertEquals(expected.turnId, done.turnId)
        assertEquals(expected.runId, done.runId)
        assertEquals("completed", done.status)
    }

    private fun observedAssistant(id: String, seq: Long, conversationId: String = "conv-auto") = streamDelta(
        "agent-1", conversationId, seq,
        """{"message_type":"assistant_message","logical_message_id":"lm-$id","turn_id":"turn-$id","run_id":"run-$id","content":"Reply"}""",
    )
}
