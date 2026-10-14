package com.letta.mobile.data.runtime

import com.letta.mobile.util.Telemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.7: every (phase, event) pair of the turn's completion lifecycle. A legal pair
 * moves to exactly the expected phase; an illegal one is rejected (the machine stays put and records
 * `turn.illegal_transition`), never silently accepted.
 */
class TurnStateMachineTest {

    /** The whole table, written out. A pair missing here is expected to be illegal. */
    private val expected: Map<Pair<TurnPhase, TurnEvent>, TurnPhase> = mapOf(
        (TurnPhase.Streaming to TurnEvent.CompletionHeld) to TurnPhase.CompletionPending,
        (TurnPhase.Streaming to TurnEvent.SpeculativeCompletionHeld) to TurnPhase.SpeculativeCompletionPending,
        (TurnPhase.Streaming to TurnEvent.ContinuedActivity) to TurnPhase.Streaming,
        (TurnPhase.Streaming to TurnEvent.TerminalPublished) to TurnPhase.Terminal,
        (TurnPhase.CompletionPending to TurnEvent.CompletionHeld) to TurnPhase.CompletionPending,
        (TurnPhase.CompletionPending to TurnEvent.SpeculativeCompletionHeld) to TurnPhase.SpeculativeCompletionPending,
        (TurnPhase.CompletionPending to TurnEvent.ContinuedActivity) to TurnPhase.CompletionPending,
        (TurnPhase.CompletionPending to TurnEvent.TerminalPublished) to TurnPhase.Terminal,
        (TurnPhase.SpeculativeCompletionPending to TurnEvent.CompletionHeld) to TurnPhase.SpeculativeCompletionPending,
        (TurnPhase.SpeculativeCompletionPending to TurnEvent.SpeculativeCompletionHeld) to TurnPhase.SpeculativeCompletionPending,
        (TurnPhase.SpeculativeCompletionPending to TurnEvent.ContinuedActivity) to TurnPhase.Streaming,
        (TurnPhase.SpeculativeCompletionPending to TurnEvent.TerminalPublished) to TurnPhase.Terminal,
    )

    @Test
    fun everyPhaseEventPairIsEitherTheExpectedMoveOrRejected() {
        val pairs = TurnPhase.all.flatMap { phase -> TurnEvent.entries.map { event -> phase to event } }
        assertEquals(16, pairs.size)

        pairs.forEach { (phase, event) ->
            assertEquals(expected[phase to event], turnTransition(phase, event), "$phase + $event")
        }
        val illegal = pairs.filter { it !in expected }
        assertEquals(TurnEvent.entries.map { TurnPhase.Terminal to it }, illegal)
    }

    @Test
    fun machineRejectsAndRecordsAnIllegalTransitionWithoutMoving() {
        Telemetry.clear()
        val machine = TurnStateMachine("test", TurnPhase.Streaming, ::turnTransition)
        assertEquals(TurnStep<TurnPhase>(TurnPhase.Streaming, TurnPhase.Terminal), machine.fire(TurnEvent.TerminalPublished))

        assertNull(machine.fire(TurnEvent.TerminalPublished), "a second terminal must be rejected")
        assertNull(machine.fire(TurnEvent.CompletionHeld), "a completion after the terminal must be rejected")

        assertEquals(TurnPhase.Terminal, machine.phase)
        val recorded = Telemetry.snapshot().filter { it.name == "turn.illegal_transition" }
        // The snapshot's order is the telemetry buffer's, so compare as a set.
        assertEquals(setOf("TerminalPublished", "CompletionHeld"), recorded.map { it.attrs["event"] }.toSet())
        assertEquals(2, recorded.size)
        assertTrue(recorded.all { it.attrs["machine"] == "test" && it.attrs["phase"] == "Terminal" })
    }

    @Test
    fun speculationEvidenceNeedsAToolReturnBeforeTheAnswer() {
        assertEquals(SpeculationEvidence.None, SpeculationEvidence.None.afterAssistant())
        val returned = SpeculationEvidence.None.afterToolReturn()
        assertEquals(SpeculationEvidence.ToolReturned, returned)
        assertEquals(SpeculationEvidence.ToolReturned, returned.afterToolReturn())
        val answered = returned.afterAssistant()
        assertEquals(SpeculationEvidence.AnsweredAfterToolReturn, answered)
        assertEquals(SpeculationEvidence.AnsweredAfterToolReturn, answered.afterToolReturn())
        assertEquals(SpeculationEvidence.AnsweredAfterToolReturn, answered.afterAssistant())
    }
}
