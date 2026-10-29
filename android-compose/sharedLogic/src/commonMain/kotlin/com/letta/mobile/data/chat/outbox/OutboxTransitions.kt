package com.letta.mobile.data.chat.outbox

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
        val target = when {
            entry.state.isTerminal -> return OutboxTransition.Rejected(OutboxRejection.Terminal)
            event.fenced && attempt != entry.attempt -> return OutboxTransition.Rejected(OutboxRejection.StaleAttempt)
            event == OutboxEvent.EchoIngested -> entry.moved(OutboxState.Confirmed, nowMillis)
            else -> move(entry, event, nowMillis)
        }
        return target?.let { OutboxTransition.Applied(it) } ?: OutboxTransition.Rejected(OutboxRejection.NotAllowed)
    }

    private fun move(entry: OutboxEntry, event: OutboxEvent, now: Long): OutboxEntry? = when (entry.state) {
        OutboxState.Queued -> fromQueued(entry, event, now)
        OutboxState.Dispatching -> fromDispatching(entry, event, now)
        OutboxState.Accepted -> fromAccepted(entry, event, now)
        OutboxState.Unknown -> fromUnknown(entry, event, now)
        OutboxState.Failed -> fromFailed(entry, event, now)
        OutboxState.Confirmed, OutboxState.Dismissed -> null
    }

    private fun fromQueued(entry: OutboxEntry, event: OutboxEvent, now: Long) = when (event) {
        OutboxEvent.DispatchStart -> entry.moved(OutboxState.Dispatching, now)
        OutboxEvent.UserAbort -> entry.moved(OutboxState.Dismissed, now)
        else -> null
    }

    private fun fromDispatching(entry: OutboxEntry, event: OutboxEvent, now: Long) = when (event) {
        OutboxEvent.TransportAccepted -> entry.moved(OutboxState.Accepted, now)
        OutboxEvent.TransportRejected -> entry.failed(OutboxFailure.NotAccepted, now)
        OutboxEvent.Disconnected -> entry.moved(OutboxState.Unknown, now)
        else -> null
    }

    private fun fromAccepted(entry: OutboxEntry, event: OutboxEvent, now: Long) = when (event) {
        // The turn ended before its echo arrived: the echo horizon starts again from here.
        OutboxEvent.TurnSettled -> entry.moved(OutboxState.Accepted, now)
        // A missing echo is a reason to read history, never a reason to fail.
        OutboxEvent.EchoTimeout, OutboxEvent.Disconnected -> entry.moved(OutboxState.Unknown, now)
        else -> null
    }

    private fun fromUnknown(entry: OutboxEntry, event: OutboxEvent, now: Long) = when (event) {
        OutboxEvent.ProbeFoundEcho -> entry.moved(OutboxState.Confirmed, now)
        OutboxEvent.ProbeNoEcho -> entry.noEcho(now)
        else -> null
    }

    /** History ran past the prompt without its echo: resend only when the user asked to retry. */
    private fun OutboxEntry.noEcho(now: Long): OutboxEntry =
        if (resendOnNoEcho) {
            moved(OutboxState.Queued, now).copy(attempt = attempt + 1)
        } else {
            failed(OutboxFailure.Unconfirmed, now)
        }

    private fun fromFailed(entry: OutboxEntry, event: OutboxEvent, now: Long) = when (event) {
        OutboxEvent.UserDismiss -> entry.moved(OutboxState.Dismissed, now)
        OutboxEvent.UserRetry -> entry.retried(now)
        else -> null
    }

    /**
     * A prompt that never reached the server is queued again. One that may have reached it is
     * probed first and resent only when history shows no echo, so a retry cannot duplicate it.
     */
    private fun OutboxEntry.retried(now: Long): OutboxEntry = when (failure) {
        OutboxFailure.Unconfirmed ->
            moved(OutboxState.Unknown, now).copy(attempt = attempt + 1, resendOnNoEcho = true)
        else -> moved(OutboxState.Queued, now).copy(attempt = attempt + 1)
    }

    private fun OutboxEntry.moved(state: OutboxState, now: Long) =
        copy(state = state, stateSinceMillis = now, failure = null, resendOnNoEcho = false)

    private fun OutboxEntry.failed(failure: OutboxFailure, now: Long) =
        copy(state = OutboxState.Failed, stateSinceMillis = now, failure = failure, resendOnNoEcho = false)
}
