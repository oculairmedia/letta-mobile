package com.letta.mobile.data.runtime

import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-by4vm.7: an explicit state machine over one turn-lifecycle dimension. The phase only
 * changes through [fire], which looks the (phase, event) pair up in a transition table. A pair the
 * table does not list is an illegal transition: it is rejected (the phase stays) and recorded as
 * `turn.illegal_transition`, never silently ignored.
 *
 * The tables are the turn's completion lifecycle ([turnTransition], owned by [TurnDraftProcessor])
 * and its server-boundary facts ([boundaryTransition], owned by [TurnBoundaryGate]).
 * [TurnLeasePhase] stays the lease's own state.
 *
 * Not thread-safe: each owner calls it from one place (the gate under its lock).
 */
internal class TurnStateMachine<P : Any, E : Any>(
    private val name: String,
    initial: P,
    private val table: (P, E) -> P?,
) {
    var phase: P = initial
        private set

    /** Applies [event]: the step taken, or null when the table rejects it from the current phase. */
    fun fire(event: E): TurnStep<P>? {
        val from = phase
        val to = table(from, event)
        if (to == null) {
            Telemetry.event(
                "AppServerTurnEngine", "turn.illegal_transition",
                "machine" to name,
                "phase" to from.toString(),
                "event" to event.toString(),
                level = Telemetry.Level.WARN,
            )
            return null
        }
        phase = to
        return TurnStep(from, to)
    }
}

/** One accepted transition. */
internal data class TurnStep<P>(val from: P, val to: P)

/**
 * The completion lifecycle of one turn's drafts ([TurnDraftProcessor]). It replaces the processor's
 * `terminalArmed`, `speculativeCompletionArmed` and `terminalEmitted` flags.
 */
internal sealed interface TurnPhase {
    /** No completion is held: drafts stream straight through. */
    data object Streaming : TurnPhase

    /** A Completed lifecycle is held while the settle window runs; more frames do not cancel it. */
    data object CompletionPending : TurnPhase

    /**
     * A completion inferred from usage after a tool round (no server terminal yet) is held; any
     * further tool or assistant activity cancels it.
     */
    data object SpeculativeCompletionPending : TurnPhase

    /** The turn's terminal went out. Nothing moves the turn on from here. */
    data object Terminal : TurnPhase

    companion object {
        val all: List<TurnPhase> = listOf(Streaming, CompletionPending, SpeculativeCompletionPending, Terminal)
    }
}

internal enum class TurnEvent {
    /** A Completed lifecycle arrived (non-authoritative): hold it for the settle window. */
    CompletionHeld,

    /** Usage statistics after a tool return and an answer: speculatively hold a completion. */
    SpeculativeCompletionHeld,

    /** A tool call, tool return or assistant frame: the turn is still going. */
    ContinuedActivity,

    /** The turn's terminal was emitted. */
    TerminalPublished,
}

private val turnTransitions: Map<Pair<TurnPhase, TurnEvent>, TurnPhase> = buildMap {
    val pending = TurnPhase.CompletionPending
    val speculative = TurnPhase.SpeculativeCompletionPending
    put(TurnPhase.Streaming to TurnEvent.CompletionHeld, pending)
    put(TurnPhase.Streaming to TurnEvent.SpeculativeCompletionHeld, speculative)
    put(TurnPhase.Streaming to TurnEvent.ContinuedActivity, TurnPhase.Streaming)
    // A real completion replaces the held draft; the window already running keeps going.
    put(pending to TurnEvent.CompletionHeld, pending)
    // Usage after a tool round marks even a held completion speculative (later activity cancels it).
    put(pending to TurnEvent.SpeculativeCompletionHeld, speculative)
    put(pending to TurnEvent.ContinuedActivity, pending)
    put(speculative to TurnEvent.CompletionHeld, speculative)
    put(speculative to TurnEvent.SpeculativeCompletionHeld, speculative)
    // The speculation was wrong: the held completion is dropped.
    put(speculative to TurnEvent.ContinuedActivity, TurnPhase.Streaming)
    listOf(TurnPhase.Streaming, pending, speculative).forEach { phase ->
        put(phase to TurnEvent.TerminalPublished, TurnPhase.Terminal)
    }
    // Terminal has no outgoing transition: a second terminal, or activity after it, is illegal.
}

/** The completion-lifecycle transition table; null for an illegal transition. */
internal fun turnTransition(phase: TurnPhase, event: TurnEvent): TurnPhase? = turnTransitions[phase to event]

/**
 * letta-mobile-by4vm.7: what this turn has shown towards a speculative completion, replacing
 * `sawToolReturn` / `sawAssistantAfterToolReturn`. Every move between these is legal.
 */
internal enum class SpeculationEvidence {
    None,
    ToolReturned,

    /** An assistant frame followed a tool return: usage statistics now may end the turn. */
    AnsweredAfterToolReturn,
    ;

    fun afterToolReturn(): SpeculationEvidence = if (this == None) ToolReturned else this

    fun afterAssistant(): SpeculationEvidence = if (this == None) None else AnsweredAfterToolReturn
}
