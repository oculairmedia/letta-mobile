package com.letta.mobile.data.secrets.client

import com.letta.mobile.data.secrets.request.SecretFailureReason
import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.data.secrets.request.SecretRequestRefusal
import com.letta.mobile.data.secrets.request.SecretServiceError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every state against every event: the allowed transitions are listed, and everything else changes nothing. */
class SecretFormMachineTest {
    private val saved = FakeSecretRequestClient.receipt(SecretReceiptStatus.SAVED)
    private val empty = SecretFormState.Editing(SecretInputPresence.EMPTY)
    private val entered = SecretFormState.Editing(SecretInputPresence.ENTERED)
    private val retryable = SecretFormState.Failed(SecretFormFailure.Unreachable)
    private val closedFailure = SecretFormState.Failed(SecretFormFailure.WriteFailed(SecretFailureReason.VAULT_LOCKED))
    private val savedAnswer = SecretFormEvent.Answered(SecretClientOutcome.Settled(saved))

    private val states = listOf(
        SecretFormState.Idle, empty, entered, SecretFormState.Submitting, SecretFormState.Done(saved),
        retryable, closedFailure, SecretFormState.Expired, SecretFormState.Declined,
    )
    private val events = listOf(
        SecretFormEvent.Open, SecretFormEvent.Input(SecretInputPresence.ENTERED), SecretFormEvent.Input(SecretInputPresence.EMPTY),
        SecretFormEvent.Submit, savedAnswer, SecretFormEvent.Decline, SecretFormEvent.Expire, SecretFormEvent.Retry,
    )

    /** (state, event) to the state it leads to; every pair not listed must leave the state unchanged. */
    private val allowed: Map<Pair<SecretFormState, SecretFormEvent>, SecretFormState> = mapOf(
        (SecretFormState.Idle to SecretFormEvent.Open) to empty,
        (SecretFormState.Idle to SecretFormEvent.Decline) to SecretFormState.Declined,
        (SecretFormState.Idle to SecretFormEvent.Expire) to SecretFormState.Expired,
        (empty to SecretFormEvent.Input(SecretInputPresence.ENTERED)) to entered,
        (empty to SecretFormEvent.Decline) to SecretFormState.Declined,
        (empty to SecretFormEvent.Expire) to SecretFormState.Expired,
        (entered to SecretFormEvent.Input(SecretInputPresence.EMPTY)) to empty,
        (entered to SecretFormEvent.Submit) to SecretFormState.Submitting,
        (entered to SecretFormEvent.Decline) to SecretFormState.Declined,
        (entered to SecretFormEvent.Expire) to SecretFormState.Expired,
        (SecretFormState.Submitting to savedAnswer) to SecretFormState.Done(saved),
        (retryable to SecretFormEvent.Retry) to empty,
        (retryable to SecretFormEvent.Decline) to SecretFormState.Declined,
        (retryable to SecretFormEvent.Expire) to SecretFormState.Expired,
    )

    @Test
    fun everyStateAndEventFollowsTheTable() {
        val wrong = states.flatMap { state -> events.map { event -> state to event } }.mapNotNull { pair ->
            val expected = allowed[pair] ?: pair.first
            val actual = SecretFormMachine.next(pair.first, pair.second)
            "${pair.first} + ${pair.second}: expected $expected, got $actual".takeIf { actual != expected }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun aDoubleSubmitAnEmptySubmitAndADeclineWhileSubmittingDoNothing() {
        val submitting = SecretFormMachine.next(entered, SecretFormEvent.Submit)
        assertEquals(SecretFormState.Submitting, submitting)
        assertEquals(SecretFormState.Submitting, SecretFormMachine.next(submitting, SecretFormEvent.Submit))
        assertEquals(empty, SecretFormMachine.next(empty, SecretFormEvent.Submit))
        assertEquals(SecretFormState.Submitting, SecretFormMachine.next(submitting, SecretFormEvent.Decline))
        assertEquals(SecretFormState.Submitting, SecretFormMachine.next(submitting, SecretFormEvent.Expire))
        assertFalse(SecretFormMachine.canSubmit(empty))
        assertTrue(SecretFormMachine.canSubmit(entered))
    }

    @Test
    fun eachHostAnswerLeadsToItsState() {
        val rows = listOf(
            SecretClientOutcome.Settled(saved) to SecretFormState.Done(saved),
            receiptOf(SecretReceiptStatus.CANCELLED) to SecretFormState.Declined,
            receiptOf(SecretReceiptStatus.EXPIRED) to SecretFormState.Expired,
            SecretClientOutcome.Settled(saved.copy(status = SecretReceiptStatus.FAILED, reason = SecretFailureReason.VERIFY_FAILED)) to
                SecretFormState.Failed(SecretFormFailure.WriteFailed(SecretFailureReason.VERIFY_FAILED)),
            refusalOf(SecretServiceError.NOT_PENDING) to SecretFormState.Expired,
            refusalOf(SecretServiceError.INVALID_VALUE) to
                SecretFormState.Failed(SecretFormFailure.Refused(SecretServiceError.INVALID_VALUE)),
            refusalOf(SecretServiceError.FORBIDDEN) to SecretFormState.Failed(SecretFormFailure.Refused(SecretServiceError.FORBIDDEN)),
            SecretClientOutcome.Unreachable to retryable,
        )
        rows.forEach { (outcome, expected) -> assertEquals(expected, SecretFormMachine.outcomeState(outcome), "$outcome") }
    }

    @Test
    fun onlyFailuresThatLeaveTheRequestPendingCanBeRetried() {
        val rows = listOf(
            SecretFormFailure.Unreachable to true,
            SecretFormFailure.Refused(SecretServiceError.INVALID_VALUE) to true,
            SecretFormFailure.Refused(SecretServiceError.JOURNAL_UNAVAILABLE) to true,
            SecretFormFailure.Refused(SecretServiceError.FORBIDDEN) to false,
            SecretFormFailure.WriteFailed(SecretFailureReason.VAULT_LOCKED) to false,
        )
        rows.forEach { (failure, retryable) -> assertEquals(retryable, failure.retryable, "$failure") }
        val finals = states.filter(SecretFormMachine::isFinal)
        assertEquals(listOf(SecretFormState.Done(saved), closedFailure, SecretFormState.Expired, SecretFormState.Declined), finals)
    }

    private fun receiptOf(status: SecretReceiptStatus) = SecretClientOutcome.Settled(saved.copy(status = status))

    private fun refusalOf(error: SecretServiceError) = SecretClientOutcome.Refused(SecretRequestRefusal(error))
}
