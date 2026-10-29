package com.letta.mobile.data.timeline.api

/** Something that happened to an outbox entry. Only [OutboxTransitions.next] turns it into a state. */
internal enum class OutboxEvent(
    /** A fenced event is about one attempt and is stale once the entry has moved to another. */
    val fenced: Boolean,
) {
    DispatchStart(fenced = true),
    TransportAccepted(fenced = true),
    TransportRejected(fenced = true),
    Disconnected(fenced = true),
    TurnSettled(fenced = true),
    EchoTimeout(fenced = true),
    ProbeFoundEcho(fenced = true),
    ProbeNoEcho(fenced = true),

    /** The user echo with this otid was ingested. An echo always wins, whatever the attempt. */
    EchoIngested(fenced = false),
    UserRetry(fenced = false),

    /** Retry that resends at once, accepting a possible duplicate when the prompt was received. */
    UserResendAnyway(fenced = false),
    UserDismiss(fenced = false),
    UserAbort(fenced = false),
}

/** The outcome of offering an event to an entry. */
internal sealed interface OutboxTransition {
    data class Applied(val entry: OutboxEntry) : OutboxTransition

    data class Rejected(val reason: OutboxRejection) : OutboxTransition
}

internal enum class OutboxRejection {
    /** The entry is Confirmed or Dismissed; it takes no further events. */
    Terminal,

    /** A fenced event named another attempt than the entry's current one. */
    StaleAttempt,

    /** The table has no move for this event in this state. */
    NotAllowed,
}

/** Why [OutboxTransitions.admit] refused a new prompt. Admission happens before any transport call. */
internal enum class OutboxAdmission { Admitted, AlreadyQueued, IdentityConflict, OverBudget }

/**
 * The outbox's one transition function. It is pure: callers persist an [OutboxTransition.Applied]
 * entry with a compare-and-set on `(otid, attempt)` and log a rejection. Keeping every move here is
 * what replaces the scattered fences (attempt guards, the late-acceptance rule, dispatch leases).
 */
internal object OutboxTransitions {
    /** Live (non-terminal) entries one conversation may hold. */
    const val BUDGET: Int = 64

    fun admit(live: List<OutboxEntry>, candidate: OutboxEntry, budget: Int = BUDGET): OutboxAdmission {
        val existing = live.firstOrNull { it.otid == candidate.otid }
        return when {
            existing == null && live.count { !it.state.isTerminal } >= budget -> OutboxAdmission.OverBudget
            existing == null -> OutboxAdmission.Admitted
            existing.content == candidate.content && existing.attachments == candidate.attachments ->
                OutboxAdmission.AlreadyQueued
            else -> OutboxAdmission.IdentityConflict
        }
    }

    /**
     * Offers [event] to [entry] at [nowMillis]. A fenced event must carry the [attempt] it is about;
     * user events and the echo act on the current attempt.
     */
    fun next(entry: OutboxEntry, event: OutboxEvent, nowMillis: Long, attempt: Long? = null): OutboxTransition {
        require(!event.fenced || attempt != null) { "$event must name its attempt" }
        val move = Move(entry, nowMillis)
        val target = when {
            entry.state.isTerminal -> return OutboxTransition.Rejected(OutboxRejection.Terminal)
            event.fenced && attempt != entry.attempt -> return OutboxTransition.Rejected(OutboxRejection.StaleAttempt)
            event == OutboxEvent.EchoIngested -> move.enter(OutboxState.Confirmed)
            else -> move.on(event)
        }
        return target?.let { OutboxTransition.Applied(it) } ?: OutboxTransition.Rejected(OutboxRejection.NotAllowed)
    }

    /** One entry moving at one instant; the table, split by the state it leaves. */
    private class Move(private val entry: OutboxEntry, private val atMillis: Long) {
        fun on(event: OutboxEvent): OutboxEntry? = when (entry.state) {
            OutboxState.Queued -> fromQueued(event)
            OutboxState.Dispatching -> fromDispatching(event)
            OutboxState.Accepted -> fromAccepted(event)
            OutboxState.Unknown -> fromUnknown(event)
            OutboxState.Failed -> fromFailed(event)
            OutboxState.Confirmed, OutboxState.Dismissed -> null
        }

        private fun fromQueued(event: OutboxEvent) = when (event) {
            OutboxEvent.DispatchStart -> enter(OutboxState.Dispatching)
            OutboxEvent.UserAbort -> enter(OutboxState.Dismissed)
            else -> null
        }

        private fun fromDispatching(event: OutboxEvent) = when (event) {
            OutboxEvent.TransportAccepted -> enter(OutboxState.Accepted)
            OutboxEvent.TransportRejected -> failed(OutboxFailure.NotAccepted)
            OutboxEvent.Disconnected -> enter(OutboxState.Unknown)
            else -> null
        }

        private fun fromAccepted(event: OutboxEvent) = when (event) {
            // The turn ended before its echo arrived: the echo horizon starts again from here.
            OutboxEvent.TurnSettled -> enter(OutboxState.Accepted)
            // A missing echo is a reason to read history, never a reason to fail.
            OutboxEvent.EchoTimeout, OutboxEvent.Disconnected -> enter(OutboxState.Unknown)
            else -> null
        }

        private fun fromUnknown(event: OutboxEvent) = when (event) {
            OutboxEvent.ProbeFoundEcho -> enter(OutboxState.Confirmed)
            OutboxEvent.ProbeNoEcho -> noEcho()
            else -> null
        }

        private fun fromFailed(event: OutboxEvent) = when (event) {
            OutboxEvent.UserDismiss -> enter(OutboxState.Dismissed)
            OutboxEvent.UserRetry -> retried()
            OutboxEvent.UserResendAnyway -> enter(OutboxState.Queued).nextAttempt()
            else -> null
        }

        /** History ran past the prompt without its echo: resend only when the user asked to retry. */
        private fun noEcho(): OutboxEntry =
            if (entry.resendOnNoEcho) enter(OutboxState.Queued).nextAttempt() else failed(OutboxFailure.Unconfirmed)

        /**
         * A prompt that never reached the server is queued again. One that may have reached it is
         * probed first and resent only when history shows no echo, so a retry cannot duplicate it.
         */
        private fun retried(): OutboxEntry = when (entry.failure) {
            OutboxFailure.Unconfirmed -> enter(OutboxState.Unknown).nextAttempt().copy(resendOnNoEcho = true)
            else -> enter(OutboxState.Queued).nextAttempt()
        }

        fun enter(state: OutboxState): OutboxEntry =
            entry.copy(state = state, stateSinceMillis = atMillis, failure = null, resendOnNoEcho = false)

        private fun failed(failure: OutboxFailure): OutboxEntry =
            entry.copy(state = OutboxState.Failed, stateSinceMillis = atMillis, failure = failure, resendOnNoEcho = false)

        private fun OutboxEntry.nextAttempt(): OutboxEntry = copy(attempt = attempt + 1)
    }
}
