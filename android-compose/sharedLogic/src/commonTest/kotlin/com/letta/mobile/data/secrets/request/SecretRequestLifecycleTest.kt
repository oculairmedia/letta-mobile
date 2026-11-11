package com.letta.mobile.data.secrets.request

import com.letta.mobile.data.secrets.request.SecretRequestState.CANCELLED
import com.letta.mobile.data.secrets.request.SecretRequestState.EXPIRED
import com.letta.mobile.data.secrets.request.SecretRequestState.FAILED
import com.letta.mobile.data.secrets.request.SecretRequestState.PENDING
import com.letta.mobile.data.secrets.request.SecretRequestState.SAVED
import com.letta.mobile.data.secrets.request.SecretRequestState.SUBMITTING
import com.letta.mobile.data.secrets.request.SecretRequestTestData.NOW
import com.letta.mobile.data.secrets.request.SecretRequestTestData.record
import com.letta.mobile.data.secrets.request.SecretRequestTestData.status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class SecretRequestLifecycleTest {
    private val fail = SecretRequestEvent.Fail(SecretFailureReason.VAULT_LOCKED)
    private val events = listOf(
        SecretRequestEvent.Claim,
        SecretRequestEvent.Save,
        fail,
        SecretRequestEvent.Cancel,
        SecretRequestEvent.Expire,
        SecretRequestEvent.Interrupt,
    )
    private val beforeExpiry: Instant = NOW + 1.milliseconds
    private val atExpiry: Instant = NOW + SecretRequestLimits.DEFAULT_TTL_MS.milliseconds

    /** Expected outcome: a state for an applied event, a refusal otherwise. */
    private sealed interface Expected {
        data class To(val state: SecretRequestState, val reason: SecretFailureReason? = null) : Expected
        data class No(val refusal: TransitionRefusal) : Expected
    }

    private fun expected(state: SecretRequestState, event: SecretRequestEvent, due: Boolean): Expected = when (state) {
        PENDING -> if (due) pendingDue(event) else pendingNotDue(event)
        SUBMITTING -> submitting(event)
        else -> terminal(event)
    }

    private fun pendingNotDue(event: SecretRequestEvent): Expected = when (event) {
        SecretRequestEvent.Claim -> Expected.To(SUBMITTING)
        SecretRequestEvent.Cancel -> Expected.To(CANCELLED)
        SecretRequestEvent.Expire -> Expected.No(TransitionRefusal.NOT_DUE)
        else -> Expected.No(TransitionRefusal.NOT_SUBMITTING)
    }

    private fun pendingDue(event: SecretRequestEvent): Expected = when (event) {
        SecretRequestEvent.Claim, SecretRequestEvent.Cancel -> Expected.No(TransitionRefusal.OVERDUE)
        SecretRequestEvent.Expire -> Expected.To(EXPIRED)
        else -> Expected.No(TransitionRefusal.NOT_SUBMITTING)
    }

    private fun submitting(event: SecretRequestEvent): Expected = when (event) {
        SecretRequestEvent.Save -> Expected.To(SAVED)
        fail -> Expected.To(FAILED, SecretFailureReason.VAULT_LOCKED)
        SecretRequestEvent.Interrupt -> Expected.To(FAILED, SecretFailureReason.INTERRUPTED)
        else -> Expected.No(TransitionRefusal.NOT_PENDING)
    }

    private fun terminal(event: SecretRequestEvent): Expected = when (event) {
        SecretRequestEvent.Claim, SecretRequestEvent.Cancel, SecretRequestEvent.Expire -> Expected.No(TransitionRefusal.NOT_PENDING)
        else -> Expected.No(TransitionRefusal.NOT_SUBMITTING)
    }

    private fun actual(transition: SecretRequestTransition): Expected = when (transition) {
        is SecretRequestTransition.Applied -> Expected.To(transition.record.status.state, transition.record.status.reason)
        is SecretRequestTransition.Refused -> Expected.No(transition.refusal)
    }

    private fun applied(record: SecretRequestRecord, event: SecretRequestEvent): SecretRequestRecord =
        (SecretRequestLifecycle.apply(record, event, beforeExpiry) as SecretRequestTransition.Applied).record

    private class Case(val state: SecretRequestState, val event: SecretRequestEvent, val due: Boolean)

    @Test
    fun everyStateAndEventFollowsTheTableBeforeAndAtExpiry() {
        val cases = SecretRequestState.entries.flatMap { state ->
            events.flatMap { event -> listOf(false, true).map { due -> Case(state, event, due) } }
        }
        assertEquals(SecretRequestState.entries.size * events.size * 2, cases.size)
        for (case in cases) {
            val now = if (case.due) atExpiry else beforeExpiry
            val result = SecretRequestLifecycle.apply(record(status = status(case.state)), case.event, now)
            assertEquals(expected(case.state, case.event, case.due), actual(result), "${case.state} + ${case.event} (due=${case.due})")
        }
    }

    @Test
    fun terminalTransitionsStampTheSettlementTimeAndKeepTheFixedParts() {
        val submitting = record(status = status(SUBMITTING))
        val saved = applied(submitting, SecretRequestEvent.Save)
        assertEquals(beforeExpiry.toEpochMilliseconds(), saved.status.settledAtMs)
        assertNull(saved.status.reason)
        assertEquals(submitting.details, saved.details)
        assertEquals(submitting.timing, saved.timing)
        val claimed = applied(record(), SecretRequestEvent.Claim)
        assertNull(claimed.status.settledAtMs)
    }

    @Test
    fun receiptsExistOnlyForTerminalStatesAndCarryTheTargetAndReason() {
        assertNull(record(status = status(PENDING)).receipt())
        assertNull(record(status = status(SUBMITTING)).receipt())
        val failed = record(status = status(FAILED)).receipt()
        assertEquals(SecretRequestReceipt(SecretReceiptStatus.FAILED, SecretRequestTestData.TARGET.item,
            SecretRequestTestData.TARGET.field, SecretFailureReason.VAULT_ERROR), failed)
        assertEquals(SecretReceiptStatus.SAVED, record(status = status(SAVED)).receipt()?.status)
        assertEquals(SecretReceiptStatus.CANCELLED, record(status = status(CANCELLED)).receipt()?.status)
        assertEquals(SecretReceiptStatus.EXPIRED, record(status = status(EXPIRED)).receipt()?.status)
    }

    @Test
    fun bulkHelpersExpireDueRequestsRecoverInterruptedWritesAndPruneOldSettledOnes() {
        val records = listOf(
            record(SecretRequestTestData.id(1), status(PENDING)),
            record(SecretRequestTestData.id(2), status(SUBMITTING)),
            record(SecretRequestTestData.id(3), status(SAVED)),
        )
        assertEquals(listOf(EXPIRED, SUBMITTING, SAVED), SecretRequestLifecycle.expireDue(records, atExpiry).map { it.status.state })
        assertEquals(listOf(PENDING, SUBMITTING, SAVED), SecretRequestLifecycle.expireDue(records, beforeExpiry).map { it.status.state })
        val recovered = SecretRequestLifecycle.recoverInterrupted(records, beforeExpiry)
        assertEquals(listOf(PENDING, FAILED, SAVED), recovered.map { it.status.state })
        assertEquals(SecretFailureReason.INTERRUPTED, recovered[1].status.reason)

        val retention = SecretRequestLimits.SETTLED_RETENTION_MS.milliseconds
        assertEquals(records, SecretRequestLifecycle.prune(records, NOW + retention))
        val pruned = SecretRequestLifecycle.prune(records, NOW + retention + 1.milliseconds)
        assertEquals(listOf(SecretRequestTestData.id(1), SecretRequestTestData.id(2)), pruned.map { it.requestId })
    }
}
