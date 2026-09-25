package com.letta.mobile.data.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * letta-mobile-qygvv.12: what the engine concluded about one turn's input, in App Server
 * `input_accepted` terms. [disposition] is `started` or `queued` when [accepted]; [error] is the
 * failure text when it is not.
 */
data class TurnInputAcknowledgement(
    val accepted: Boolean,
    val disposition: String?,
    val error: String? = null,
) {
    companion object {
        const val STARTED = "started"
        const val QUEUED = "queued"

        val Started = TurnInputAcknowledgement(accepted = true, disposition = STARTED)
        val Queued = TurnInputAcknowledgement(accepted = true, disposition = QUEUED)

        fun rejected(error: String) = TurnInputAcknowledgement(accepted = false, disposition = null, error = error)
    }
}

/**
 * letta-mobile-qygvv.12: observes the engine's input acceptance for the turn collected in this
 * coroutine context. The Iroh node runs its clients' turns itself, so it has no upstream
 * `input_accepted` to forward; it installs this element around `runTurn(...).collect` and answers
 * its client from what the engine learned. Absent (every other caller) it is a no-op.
 */
class TurnInputAcknowledgementListener(
    private val onAcknowledged: suspend (TurnInputAcknowledgement) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    suspend fun acknowledge(ack: TurnInputAcknowledgement) = onAcknowledged(ack)

    companion object Key : CoroutineContext.Key<TurnInputAcknowledgementListener>
}

/** The acknowledgement a relayed client should see for this engine-side [InputAcceptance]. */
internal fun InputAcceptance.toAcknowledgement(): TurnInputAcknowledgement = when (this) {
    InputAcceptance.Started -> TurnInputAcknowledgement.Started
    InputAcceptance.Queued -> TurnInputAcknowledgement.Queued
    is InputAcceptance.Failure -> TurnInputAcknowledgement.rejected(failureReason)
    // The input was sent; only the upstream ack is missing, and the turn runs as it did pre-ack.
    is InputAcceptance.Unacknowledged -> TurnInputAcknowledgement.Started
}

/** Reports [ack] to the collecting context's listener, if one is installed. */
internal suspend fun reportInputAcknowledgement(ack: TurnInputAcknowledgement) {
    currentCoroutineContext()[TurnInputAcknowledgementListener]?.acknowledge(ack)
}
