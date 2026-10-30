package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore.PendingImageSerializer
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Where one outbound prompt is in its delivery. Every move between states goes through
 * [OutboxTransitions.next]; nothing else may change an entry's state.
 *
 * The App Server does not deduplicate `client_message_id`, so once a prompt may have reached the
 * host ([Dispatching] and later) it is never sent again blindly: contact loss leads to [Unknown],
 * which only a history probe resolves.
 */
@Serializable
internal enum class OutboxState {
    /** Persisted and admitted to the budget; no transport call has been made for this attempt. */
    Queued,

    /** The worker is calling the transport for this attempt. */
    Dispatching,

    /** The transport accepted the input; waiting for the user echo carrying this otid. */
    Accepted,

    /** Contact was lost after the input may have left; resolved only by reading history. */
    Unknown,

    /** Shown as "Not sent"; [OutboxEntry.failure] says why. */
    Failed,

    /** Terminal: the echo with this otid was ingested, live or from history. */
    Confirmed,

    /** Terminal: the user aborted a queued prompt or dismissed a failed one. */
    Dismissed,
    ;

    val isTerminal: Boolean get() = this == Confirmed || this == Dismissed
}

/** Why an entry is [OutboxState.Failed]. */
@Serializable
internal enum class OutboxFailure {
    /** The transport refused the input before accepting it: it never reached the server. */
    NotAccepted,

    /** History ran past the prompt without its echo: it may or may not have been received. */
    Unconfirmed,
}

/**
 * One outbound prompt. [createdAt] (the send time as sent, ISO-8601) only places the row; every
 * timer reads [stateSinceMillis], so a retried old prompt gets a fresh echo horizon. [attempt]
 * fences events: an event carrying another attempt is stale and does not apply.
 */
@Serializable
internal data class OutboxEntry(
    val otid: String,
    val content: String,
    val attachments: List<@Serializable(with = PendingImageSerializer::class) MessageContentPart.Image>,
    val createdAt: String,
    val state: OutboxState,
    val attempt: Long,
    val stateSinceMillis: Long,
    val failure: OutboxFailure? = null,
    /** Set by a retry of an [OutboxFailure.Unconfirmed] prompt: a probe that finds no echo requeues. */
    val resendOnNoEcho: Boolean = false,
    /**
     * The [PROCESS_DISPATCH_EPOCH] of the process that last acted on this attempt. Any other value,
     * including null from an older build, means that process is gone and nothing is in flight.
     */
    val dispatchEpoch: String? = null,
) {
    init {
        require(otid.isNotBlank()) { "An outbox entry needs an otid" }
        require((state == OutboxState.Failed) == (failure != null)) { "failure is set exactly when Failed" }
        require(!resendOnNoEcho || state == OutboxState.Unknown) { "only an Unknown entry can await a resend" }
    }

    /** Queued, Dispatching, Accepted and Unknown all read as "Sending" to the user. */
    val isSending: Boolean get() = state in SENDING_STATES

    /** Retry is offered only for a failed prompt. */
    val canRetry: Boolean get() = state == OutboxState.Failed

    /** Dismiss removes a failed prompt, or aborts one that has not been dispatched yet. */
    val canDismiss: Boolean get() = state == OutboxState.Failed || state == OutboxState.Queued

    /** True when an accepted prompt has waited [horizonMillis] for its echo and needs a probe. */
    fun echoOverdue(nowMillis: Long, horizonMillis: Long): Boolean {
        require(horizonMillis > 0)
        return state == OutboxState.Accepted && nowMillis - stateSinceMillis >= horizonMillis
    }

    /**
     * The entry as it is reloaded after process death. Nothing is in flight any more, so an input
     * that may have left becomes [OutboxState.Unknown] and is probed; queued prompts resume dispatch.
     */
    fun afterRestart(nowMillis: Long): OutboxEntry = when (state) {
        OutboxState.Dispatching, OutboxState.Accepted ->
            copy(state = OutboxState.Unknown, stateSinceMillis = nowMillis)
        else -> this
    }

    /** This entry as acted on by the running process. */
    fun stampedHere(): OutboxEntry =
        if (dispatchEpoch == PROCESS_DISPATCH_EPOCH) this else copy(dispatchEpoch = PROCESS_DISPATCH_EPOCH)

    /**
     * Whether this process may still be delivering the entry, so an absent echo is not yet evidence
     * of loss: sent by this process and younger than [horizonMillis], or older while its
     * conversation is still [streaming] (a long turn's prompt keeps its owner).
     */
    fun inFlightHere(nowMillis: Long, horizonMillis: Long, streaming: Boolean): Boolean =
        state in IN_FLIGHT_STATES && dispatchEpoch == PROCESS_DISPATCH_EPOCH &&
            (streaming || nowMillis - stateSinceMillis < horizonMillis)

    companion object {
        private val IN_FLIGHT_STATES = setOf(OutboxState.Queued, OutboxState.Dispatching, OutboxState.Accepted)

        private val SENDING_STATES = setOf(
            OutboxState.Queued,
            OutboxState.Dispatching,
            OutboxState.Accepted,
            OutboxState.Unknown,
        )
    }
}

/** Identifies this process in [OutboxEntry.dispatchEpoch]; a new one after every process start. */
internal val PROCESS_DISPATCH_EPOCH: String = Random.nextLong().toULong().toString(16)
