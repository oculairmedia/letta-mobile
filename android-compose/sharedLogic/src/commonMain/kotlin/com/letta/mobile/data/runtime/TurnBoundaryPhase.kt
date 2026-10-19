package com.letta.mobile.data.runtime

/**
 * What [TurnBoundaryGate] knows about the current lease's server turn, in place
 * of its `evidenceSeen`, `abortRequested` and `roundContinues` flags. Driven through a
 * [TurnStateMachine] with [boundaryTransition].
 *
 * Not every flag combination was reachable: the round-stop fact is only ever recorded together with
 * evidence, and once an abort is requested the round-stop fact no longer matters (an idle after an
 * abort ends the turn as Cancelled either way), so five phases cover every state the flags could be in.
 */
internal sealed interface BoundaryPhase {
    /** This lease has seen a stream delta of its own (any delta that is not from a settled run). */
    val evidenceSeen: Boolean

    /** An `abort_message` was sent for this lease. */
    val abortRequested: Boolean

    /** The latest stop_reason paused the run for an approval-continuation round. */
    val roundContinues: Boolean

    /** A new lease: no evidence, no abort. */
    data object AwaitingEvidence : BoundaryPhase {
        override val evidenceSeen = false
        override val abortRequested = false
        override val roundContinues = false
    }

    /** Evidence seen; the latest round, if it stopped, stopped for good. */
    data object EvidenceSeen : BoundaryPhase {
        override val evidenceSeen = true
        override val abortRequested = false
        override val roundContinues = false
    }

    /** Evidence seen and the latest stop_reason was `requires_approval`: another round follows. */
    data object ContinuationPending : BoundaryPhase {
        override val evidenceSeen = true
        override val abortRequested = false
        override val roundContinues = true
    }

    /** Aborted before any evidence. */
    data object AbortedAwaitingEvidence : BoundaryPhase {
        override val evidenceSeen = false
        override val abortRequested = true
        override val roundContinues = false
    }

    /** Aborted with evidence: the next idle loop status ends the turn as Cancelled. */
    data object AbortedWithEvidence : BoundaryPhase {
        override val evidenceSeen = true
        override val abortRequested = true
        override val roundContinues = false
    }

    companion object {
        val all: List<BoundaryPhase> =
            listOf(AwaitingEvidence, EvidenceSeen, ContinuationPending, AbortedAwaitingEvidence, AbortedWithEvidence)
    }
}

internal enum class BoundaryEvent {
    /** A new turn lease started collecting on this runtime key. */
    LeaseBegan,

    /** A stream delta of this lease that is not a stop_reason. */
    Delta,

    /** A stop_reason that ends the round (anything but `requires_approval`). */
    RoundStopped,

    /** A `requires_approval` stop_reason: the run continues in another round. */
    RoundStoppedForApproval,

    /** An `abort_message` was sent for this key. */
    AbortRequested,
}

private val boundaryTransitions: Map<Pair<BoundaryPhase, BoundaryEvent>, BoundaryPhase> = buildMap {
    val open = listOf(BoundaryPhase.AwaitingEvidence, BoundaryPhase.EvidenceSeen, BoundaryPhase.ContinuationPending)
    val aborted = listOf(BoundaryPhase.AbortedAwaitingEvidence, BoundaryPhase.AbortedWithEvidence)
    BoundaryPhase.all.forEach { phase -> put(phase to BoundaryEvent.LeaseBegan, BoundaryPhase.AwaitingEvidence) }
    open.forEach { phase ->
        // A plain delta is evidence; it does not change what the latest round stop said.
        put(phase to BoundaryEvent.Delta, if (phase == BoundaryPhase.AwaitingEvidence) BoundaryPhase.EvidenceSeen else phase)
        put(phase to BoundaryEvent.RoundStopped, BoundaryPhase.EvidenceSeen)
        put(phase to BoundaryEvent.RoundStoppedForApproval, BoundaryPhase.ContinuationPending)
        val abortedTo = if (phase.evidenceSeen) BoundaryPhase.AbortedWithEvidence else BoundaryPhase.AbortedAwaitingEvidence
        put(phase to BoundaryEvent.AbortRequested, abortedTo)
    }
    aborted.forEach { phase ->
        listOf(BoundaryEvent.Delta, BoundaryEvent.RoundStopped, BoundaryEvent.RoundStoppedForApproval).forEach { event ->
            put(phase to event, BoundaryPhase.AbortedWithEvidence)
        }
        // A repeated abort request (the user pressed stop twice) changes nothing.
        put(phase to BoundaryEvent.AbortRequested, phase)
    }
}

/** The boundary-gate transition table; null for an illegal transition (it has none: every pair is listed). */
internal fun boundaryTransition(phase: BoundaryPhase, event: BoundaryEvent): BoundaryPhase? =
    boundaryTransitions[phase to event]
