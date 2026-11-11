package com.letta.mobile.data.secrets.client

import com.letta.mobile.data.secrets.request.SecretFailureReason
import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.data.secrets.request.SecretRequestReceipt
import com.letta.mobile.data.secrets.request.SecretServiceError

/** Whether the form's field holds anything: the only thing a form state knows about the value. */
enum class SecretInputPresence {
    /** The field is empty. */
    EMPTY,

    /** The field holds at least one character. */
    ENTERED,
}

/** Why a submission did not save, as a fixed code: never request text, provider output or a value. */
sealed interface SecretFormFailure {
    /** Whether the request is still pending, so the person may type the value again and resubmit. */
    val retryable: Boolean

    /** The host tried to write and failed for [reason]; the request is closed and cannot be retried. */
    data class WriteFailed(
        /** The host's fixed failure code. */
        val reason: SecretFailureReason,
    ) : SecretFormFailure {
        override val retryable: Boolean get() = false
    }

    /** The host refused the submission with [error]; the request may still be pending. */
    data class Refused(
        /** The host's stable refusal code. */
        val error: SecretServiceError,
    ) : SecretFormFailure {
        override val retryable: Boolean get() = error in RETRYABLE_REFUSALS
    }

    /** The submission did not reach the host, or its answer was lost. */
    data object Unreachable : SecretFormFailure {
        override val retryable: Boolean get() = true
    }

    private companion object {
        /** Refusals after which the request is still pending and nothing was written. */
        val RETRYABLE_REFUSALS = setOf(SecretServiceError.INVALID_VALUE, SecretServiceError.JOURNAL_UNAVAILABLE)
    }
}

/**
 * Where the secure form is. No state holds the value, its characters or its length: the value
 * lives only in the form's [SecretBuffer], so printing, saving or snapshotting a state leaks nothing.
 *
 * ```
 * Idle --open--> Editing --submit (value entered)--> Submitting --saved--> Done(receipt)
 *                  |  ^                                   |------failed--> Failed(reason) --retry (if allowed)--> Editing
 *                  |  '------------------------------------------------------------------------'
 *                  |--decline--> Declined          Submitting --expired/closed--> Expired
 *                  '--expire / request gone--> Expired
 * ```
 */
sealed interface SecretFormState {
    /** The form is not open yet. */
    data object Idle : SecretFormState

    /** The person is typing; [input] says only whether the field is empty. */
    data class Editing(
        /** Whether the field holds anything. */
        val input: SecretInputPresence,
    ) : SecretFormState

    /** The value was handed to the client and the form waits for the host; nothing can be edited or sent. */
    data object Submitting : SecretFormState

    /** The secret was written and verified; [receipt] is all the form shows. */
    data class Done(
        /** The receipt (status saved, item, field). */
        val receipt: SecretRequestReceipt,
    ) : SecretFormState

    /** The submission failed for [failure]; see [SecretFormFailure.retryable]. */
    data class Failed(
        /** Why it failed. */
        val failure: SecretFormFailure,
    ) : SecretFormState

    /** The request expired, or can no longer be answered (answered elsewhere, or gone from the host). */
    data object Expired : SecretFormState

    /** The person declined the request. */
    data object Declined : SecretFormState
}

/** Something that happens to the form; [SecretFormMachine.next] decides what it changes. */
sealed interface SecretFormEvent {
    /** The person opened the form. */
    data object Open : SecretFormEvent

    /** The field changed; [input] says whether it is empty now. */
    data class Input(
        /** Whether the field holds anything. */
        val input: SecretInputPresence,
    ) : SecretFormEvent

    /** The person pressed submit. */
    data object Submit : SecretFormEvent

    /** The host answered the submission with [outcome]. */
    data class Answered(
        /** The host's answer. */
        val outcome: SecretClientOutcome,
    ) : SecretFormEvent

    /** The person declined. */
    data object Decline : SecretFormEvent

    /** The request's expiry passed, or the host no longer lists it as pending. */
    data object Expire : SecretFormEvent

    /** The person asked to try again after a retryable failure. */
    data object Retry : SecretFormEvent
}

/**
 * The secure form's state machine as a pure function. Every event not allowed in a state leaves
 * the state as it is, which is what makes a double submit, a submit of an empty field, a submit
 * of an expired request and a decline during a submission impossible.
 */
object SecretFormMachine {
    /** The state after [event] in [state]. */
    fun next(state: SecretFormState, event: SecretFormEvent): SecretFormState = when (event) {
        SecretFormEvent.Open -> open(state)
        is SecretFormEvent.Input -> input(state, event.input)
        SecretFormEvent.Submit -> submit(state)
        is SecretFormEvent.Answered -> answered(state, event.outcome)
        SecretFormEvent.Decline -> closed(state, SecretFormState.Declined)
        SecretFormEvent.Expire -> closed(state, SecretFormState.Expired)
        SecretFormEvent.Retry -> retry(state)
    }

    /** Whether submit is allowed in [state]: the form is editing and the field is not empty. */
    fun canSubmit(state: SecretFormState): Boolean = (state as? SecretFormState.Editing)?.input == SecretInputPresence.ENTERED

    /** Whether the person can still answer in [state] (type, decline, or expire): open and not submitting. */
    fun isAnswerable(state: SecretFormState): Boolean = when (state) {
        SecretFormState.Idle, is SecretFormState.Editing -> true
        is SecretFormState.Failed -> state.failure.retryable
        else -> false
    }

    /** Whether [state] is final: nothing but closing the form follows it. */
    fun isFinal(state: SecretFormState): Boolean = !isAnswerable(state) && state != SecretFormState.Submitting

    private fun open(state: SecretFormState): SecretFormState =
        if (state == SecretFormState.Idle) SecretFormState.Editing(SecretInputPresence.EMPTY) else state

    private fun input(state: SecretFormState, input: SecretInputPresence): SecretFormState =
        if (state is SecretFormState.Editing) SecretFormState.Editing(input) else state

    private fun submit(state: SecretFormState): SecretFormState = if (canSubmit(state)) SecretFormState.Submitting else state

    private fun answered(state: SecretFormState, outcome: SecretClientOutcome): SecretFormState =
        if (state == SecretFormState.Submitting) outcomeState(outcome) else state

    private fun closed(state: SecretFormState, closed: SecretFormState): SecretFormState = if (isAnswerable(state)) closed else state

    private fun retry(state: SecretFormState): SecretFormState =
        if (state.isRetryable()) SecretFormState.Editing(SecretInputPresence.EMPTY) else state

    private fun SecretFormState.isRetryable(): Boolean = (this as? SecretFormState.Failed)?.failure?.retryable == true

    /** The state a host answer leads to. */
    fun outcomeState(outcome: SecretClientOutcome): SecretFormState = when (outcome) {
        is SecretClientOutcome.Settled -> receiptState(outcome.receipt)
        is SecretClientOutcome.Refused -> refusalState(outcome.refusal.error)
        SecretClientOutcome.Unreachable -> SecretFormState.Failed(SecretFormFailure.Unreachable)
    }

    private fun receiptState(receipt: SecretRequestReceipt): SecretFormState = when (receipt.status) {
        SecretReceiptStatus.SAVED -> SecretFormState.Done(receipt)
        SecretReceiptStatus.CANCELLED -> SecretFormState.Declined
        SecretReceiptStatus.EXPIRED -> SecretFormState.Expired
        SecretReceiptStatus.FAILED ->
            SecretFormState.Failed(SecretFormFailure.WriteFailed(receipt.reason ?: SecretFailureReason.VAULT_ERROR))
    }

    private fun refusalState(error: SecretServiceError): SecretFormState =
        if (error == SecretServiceError.NOT_PENDING) SecretFormState.Expired else SecretFormState.Failed(SecretFormFailure.Refused(error))
}
