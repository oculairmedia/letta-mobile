package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.request.PrincipalAccess
import com.letta.mobile.data.secrets.request.PrincipalOrigin
import com.letta.mobile.data.secrets.request.SecretFailureReason
import com.letta.mobile.data.secrets.journal.SecretJournalProblem
import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.journal.SecretRequestJournal
import com.letta.mobile.data.secrets.journal.SecretRequestJournalException
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestPrincipal
import com.letta.mobile.data.secrets.request.SecretRequestProblem
import com.letta.mobile.data.secrets.request.SecretRequestReceipt
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestTestData
import com.letta.mobile.data.secrets.request.SecretRequestTestData.TARGET
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.plugin.keystore.KeystoreCapability
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.KeystoreOperation
import com.letta.mobile.plugin.keystore.SecretField
import com.letta.mobile.plugin.keystore.SecretValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SecretRequestServiceTest {
    private val saved = SecretRequestReceipt(SecretReceiptStatus.SAVED, TARGET.item, TARGET.field)
    private val notPending = SecretServiceResult.Refused(SecretServiceError.NOT_PENDING)

    private fun failed(reason: SecretFailureReason) = SecretServiceResult.Ok(
        SecretRequestReceipt(SecretReceiptStatus.FAILED, TARGET.item, TARGET.field, reason),
    )

    private suspend fun ServiceHarness.stateOf(id: SecretRequestId): SecretRequestState? =
        journal.read().firstOrNull { it.requestId == id }?.status?.state

    @Test
    fun aHumanSubmissionIsWrittenVerifiedJournaledAndReceiptedWithoutTheValue() = runTest {
        val harness = ServiceHarness()
        val id = harness.pending()
        val value = secretOf("sentinel-happy-path")
        assertEquals(SecretServiceResult.Ok(saved), harness.service.submit(HUMAN, id, value))
        assertEquals("sentinel-happy-path", harness.provider.storedText(TARGET_REF))
        assertEquals(SecretRequestState.SAVED, harness.stateOf(id))
        assertEquals(SecretServiceResult.Ok(saved), harness.service.receipt(REQUESTER, id))
        assertEquals(SecretServiceResult.Ok(saved), harness.service.awaitReceipt(REQUESTER, id))
        assertTrue(value.isWiped)
        assertTrue(harness.provider.lent.all(SecretValue::isWiped), "every read-back is wiped")
    }

    @Test
    fun anExistingSlotIsReplacedAndItsPreviousValueIsWiped() = runTest {
        val harness = ServiceHarness()
        harness.provider.seed(TARGET_REF, "old-value")
        val id = harness.pending()
        assertEquals(SecretServiceResult.Ok(saved), harness.service.submit(HUMAN, id, secretOf("new-value")))
        assertEquals("new-value", harness.provider.storedText(TARGET_REF))
        assertEquals(2, harness.provider.lent.size)
        assertTrue(harness.provider.lent.all(SecretValue::isWiped))
    }

    @Test
    fun anExpiredRequestRefusesSubmissionWritesNothingAndReceiptsExpired() = runTest {
        val harness = ServiceHarness()
        val id = harness.pending()
        harness.clock.nowMs += SecretRequestLimits.DEFAULT_TTL_MS
        val value = secretOf("sentinel-late")
        assertEquals(notPending, harness.service.submit(HUMAN, id, value))
        assertEquals(0, harness.provider.writes)
        assertTrue(value.isWiped)
        val receipt = harness.service.receipt(REQUESTER, id) as SecretServiceResult.Ok
        assertEquals(SecretReceiptStatus.EXPIRED, receipt.value?.status)
    }

    @Test
    fun aReplayIsRefusedAndNeverWritesAgain() = runTest {
        val harness = ServiceHarness()
        val id = harness.pending()
        harness.service.submit(HUMAN, id, secretOf("first"))
        val replay = secretOf("second")
        assertEquals(notPending, harness.service.submit(HUMAN, id, replay))
        assertEquals(notPending, harness.service.cancel(HUMAN, id))
        assertEquals(1, harness.provider.writes)
        assertEquals("first", harness.provider.storedText(TARGET_REF))
        assertTrue(replay.isWiped)
    }

    @Test
    fun aConcurrentDoubleSubmitWritesOnce() = runTest {
        val harness = ServiceHarness()
        val id = harness.pending()
        val gate = CompletableDeferred<Unit>().also { harness.provider.putGate = it }
        val first = async { harness.service.submit(HUMAN, id, secretOf("first")) }
        harness.provider.putStarted.await()
        assertEquals(SecretRequestState.SUBMITTING, harness.stateOf(id))
        val second = secretOf("second")
        assertEquals(notPending, harness.service.submit(HUMAN, id, second))
        assertEquals(notPending, harness.service.cancel(HUMAN, id))
        gate.complete(Unit)
        assertEquals(SecretServiceResult.Ok(saved), first.await())
        assertEquals(1, harness.provider.writes)
        assertTrue(second.isWiped)
    }

    @Test
    fun agentOriginsAndLimitedDevicesCannotAnswerListOrRead() = runTest {
        val harness = ServiceHarness()
        val id = harness.pending()
        val forbidden = SecretServiceResult.Refused(SecretServiceError.FORBIDDEN)
        val refused = PrincipalOrigin.entries.flatMap { origin -> PrincipalAccess.entries.map { origin to it } }
            .map { (origin, access) -> SecretRequestPrincipal(HUMAN.endpoint, origin, access) }
            .filterNot(SecretRequestPrincipal::mayAnswer)
        assertEquals(PrincipalOrigin.entries.size * PrincipalAccess.entries.size - 1, refused.size)
        for (principal in refused) {
            val value = secretOf("sentinel-agent")
            assertEquals(forbidden, harness.service.submit(principal, id, value), principal.toString())
            assertEquals(forbidden, harness.service.cancel(principal, id))
            assertEquals(forbidden, harness.service.list(principal))
            assertEquals(forbidden, harness.service.get(principal, id))
            assertTrue(value.isWiped)
        }
        assertEquals(0, harness.provider.writes)
        assertEquals(SecretRequestState.PENDING, harness.stateOf(id))
    }

    @Test
    fun unknownExpiredCancelledSavedAndMalformedIdsAreIndistinguishableToASubmitter() = runTest {
        val harness = ServiceHarness()
        val expired = harness.pending()
        harness.clock.nowMs += SecretRequestLimits.DEFAULT_TTL_MS
        val cancelled = harness.pending().also { harness.service.cancel(HUMAN, it) }
        val answered = harness.pending().also { harness.service.submit(HUMAN, it, secretOf("x")) }
        val ids = listOf(SecretRequestTestData.id(99), expired, cancelled, answered, SecretRequestId("not-an-id"))
        val answers = ids.map { harness.service.submit(HUMAN, it, secretOf("sentinel-probe")) }
        assertEquals(List(ids.size) { notPending }, answers)
        assertEquals(1, harness.provider.writes)
    }

    @Test
    fun keystoreFailuresSettleAsFailedWithAFixedReasonAndStoreNothing() = runTest {
        val cases = mapOf(
            KeystoreError.Locked to SecretFailureReason.VAULT_LOCKED,
            KeystoreError.Unavailable() to SecretFailureReason.VAULT_UNAVAILABLE,
            KeystoreError.Denied(KeystoreOperation.PUT) to SecretFailureReason.VAULT_DENIED,
            KeystoreError.Unsupported(KeystoreOperation.GET) to SecretFailureReason.VAULT_UNSUPPORTED,
            KeystoreError.Internal() to SecretFailureReason.VAULT_ERROR,
        )
        for ((error, reason) in cases) {
            val harness = ServiceHarness().also { it.provider.failWith = error }
            val id = harness.pending()
            val value = secretOf("sentinel-failure")
            assertEquals(failed(reason), harness.service.submit(HUMAN, id, value), error.toString())
            assertTrue(harness.provider.refs().isEmpty())
            assertTrue(value.isWiped)
            assertEquals(failed(reason), harness.service.awaitReceipt(REQUESTER, id))
            assertEquals(notPending, harness.service.submit(HUMAN, id, secretOf("retry")), "no retry without a new request")
        }
    }

    @Test
    fun aThrowingProviderIsAFailedWriteNotACrash() = runTest {
        val harness = ServiceHarness().also { it.provider.throwOnCall = true }
        val id = harness.pending()
        assertEquals(failed(SecretFailureReason.VAULT_ERROR), harness.service.submit(HUMAN, id, secretOf("sentinel-throw")))
    }

    @Test
    fun aReadBackMismatchOnANewItemDeletesTheItem() = runTest {
        val harness = ServiceHarness().also { it.provider.corruptReadBack = true }
        val id = harness.pending()
        assertEquals(failed(SecretFailureReason.VERIFY_FAILED), harness.service.submit(HUMAN, id, secretOf("sentinel-mismatch")))
        assertTrue(harness.provider.refs().isEmpty())
        assertTrue(harness.provider.lent.all(SecretValue::isWiped))
    }

    @Test
    fun aReadBackMismatchOnAnExistingSlotRestoresThePreviousValue() = runTest {
        val harness = ServiceHarness()
        harness.provider.seed(TARGET_REF, "previous")
        val id = harness.pending()
        harness.provider.corruptReadBack = true
        assertEquals(failed(SecretFailureReason.VERIFY_FAILED), harness.service.submit(HUMAN, id, secretOf("sentinel-mismatch")))
        assertEquals("previous", harness.provider.storedText(TARGET_REF))
    }

    @Test
    fun aMismatchWhoseUndoFailsReportsRollbackFailed() = runTest {
        val harness = ServiceHarness().also {
            it.provider.corruptReadBack = true
            it.provider.failDeleteWith = KeystoreError.Unavailable()
        }
        val id = harness.pending()
        assertEquals(failed(SecretFailureReason.ROLLBACK_FAILED), harness.service.submit(HUMAN, id, secretOf("sentinel-rollback")))
    }

    @Test
    fun aNewFieldInAnExistingItemOrAnUnlistableKeystoreIsNotWritable() = runTest {
        val harness = ServiceHarness()
        harness.provider.seed(TARGET_REF.copy(field = SecretField("other")), "kept")
        val id = harness.pending()
        assertEquals(failed(SecretFailureReason.TARGET_NOT_WRITABLE), harness.service.submit(HUMAN, id, secretOf("sentinel-field")))
        assertEquals(setOf(TARGET_REF.copy(field = SecretField("other"))), harness.provider.refs())

        val unlistable = ServiceHarness(provider = TestKeystoreProvider(KeystoreCapability.entries.toSet() - KeystoreCapability.LIST))
        val other = unlistable.pending()
        assertEquals(failed(SecretFailureReason.TARGET_NOT_WRITABLE), unlistable.service.submit(HUMAN, other, secretOf("sentinel-list")))
        assertEquals(0, unlistable.provider.writes)
    }

    @Test
    fun anEmptyOrOversizedValueIsRefusedAndTheRequestStaysPending() = runTest {
        val harness = ServiceHarness()
        val id = harness.pending()
        val empty = SecretValue.ofBytes(ByteArray(0))
        val oversized = SecretValue.ofBytes(ByteArray(SecretRequestLimits.MAX_VALUE_BYTES + 1))
        val invalid = SecretServiceResult.Refused(SecretServiceError.INVALID_VALUE)
        assertEquals(invalid, harness.service.submit(HUMAN, id, empty))
        assertEquals(invalid, harness.service.submit(HUMAN, id, oversized))
        assertTrue(empty.isWiped && oversized.isWiped)
        assertEquals(SecretRequestState.PENDING, harness.stateOf(id))
        assertEquals(SecretServiceResult.Ok(saved), harness.service.submit(HUMAN, id, secretOf("now-valid")))
    }

    @Test
    fun cancellationSettlesTheRequestAndTheAgentSeesCancelled() = runTest {
        val harness = ServiceHarness()
        val id = harness.pending()
        val cancelled = SecretRequestReceipt(SecretReceiptStatus.CANCELLED, TARGET.item, TARGET.field)
        assertEquals(SecretServiceResult.Ok(cancelled), harness.service.cancel(HUMAN, id))
        assertEquals(SecretServiceResult.Ok(cancelled), harness.service.awaitReceipt(REQUESTER, id))
        assertEquals(notPending, harness.service.submit(HUMAN, id, secretOf("late")))
    }

    @Test
    fun anotherAgentCannotObserveTheReceiptAndCannotTellItFromAnUnknownId() = runTest {
        val harness = ServiceHarness()
        val id = harness.pending()
        harness.service.submit(HUMAN, id, secretOf("x"))
        val unknown = SecretServiceResult.Refused(SecretServiceError.UNKNOWN_REQUEST)
        assertEquals(unknown, harness.service.receipt(BYSTANDER, id))
        assertEquals(unknown, harness.service.awaitReceipt(BYSTANDER, id))
        assertEquals(unknown, harness.service.receipt(BYSTANDER, SecretRequestTestData.id(98)))
        assertEquals(unknown, harness.service.receipt(REQUESTER, SecretRequestTestData.id(98)))
    }

    @Test
    fun awaitingAReceiptExpiresThePendingRequestOnItsOwn() = runTest {
        val clock = SchedulerClock(this)
        val harness = ServiceHarness(clock = clock)
        val id = harness.pending()
        val receipt = harness.service.awaitReceipt(REQUESTER, id) as SecretServiceResult.Ok
        assertEquals(SecretReceiptStatus.EXPIRED, receipt.value.status)
        assertTrue(clock.nowMs >= SecretRequestTestData.NOW.toEpochMilliseconds() + SecretRequestLimits.DEFAULT_TTL_MS)
    }

    @Test
    fun theHumanCanListAndReadRequestsAndTheTargetIsFixedByTheHost() = runTest {
        val harness = ServiceHarness()
        val first = harness.pending()
        harness.clock.nowMs += 1
        val second = harness.pending(BYSTANDER)
        val listed = (harness.service.list(HUMAN) as SecretServiceResult.Ok).value.requests
        assertEquals(listOf(second, first), listed.map(SecretRequestRecord::requestId))
        val record = (harness.service.get(HUMAN, first) as SecretServiceResult.Ok).value
        assertEquals(TARGET, record.details.target)
        assertEquals(SecretRequestTestData.AGENT, record.details.requestingAgent)
        val unknown = harness.service.get(HUMAN, SecretRequestTestData.id(97))
        assertEquals(SecretServiceResult.Refused(SecretServiceError.UNKNOWN_REQUEST), unknown)
    }

    @Test
    fun invalidRequestsAndTooManyPendingAreRefusedWithTheirProblem() = runTest {
        val harness = ServiceHarness()
        val blank = harness.service.request(REQUESTER, SecretRequestTestData.request(" "))
        assertEquals(SecretServiceResult.Refused(SecretServiceError.INVALID_REQUEST, SecretRequestProblem.PURPOSE_BLANK), blank)
        repeat(SecretRequestLimits.MAX_PENDING_PER_AGENT) { harness.pending() }
        val tooMany = harness.service.request(REQUESTER, SecretRequestTestData.request())
        assertEquals(SecretServiceResult.Refused(SecretServiceError.INVALID_REQUEST, SecretRequestProblem.TOO_MANY_PENDING), tooMany)
        harness.pending(BYSTANDER)
    }

    @Test
    fun scheduledExpiryCountsWhatItExpired() = runTest {
        val harness = ServiceHarness()
        harness.pending()
        harness.pending()
        assertEquals(SecretServiceResult.Ok(0), harness.service.expire())
        val later = harness.clock.now() + SecretRequestLimits.DEFAULT_TTL_MS.milliseconds
        assertEquals(SecretServiceResult.Ok(2), harness.service.expire(later))
    }

    @Test
    fun anUnavailableJournalRefusesEverythingAndNothingIsWritten() = runTest {
        val harness = ServiceHarness(journal = BrokenJournal())
        val unavailable = SecretServiceResult.Refused(SecretServiceError.JOURNAL_UNAVAILABLE)
        assertEquals(unavailable, harness.service.request(REQUESTER, SecretRequestTestData.request()))
        val value = secretOf("sentinel-journal")
        assertEquals(unavailable, harness.service.submit(HUMAN, SecretRequestTestData.id(1), value))
        assertEquals(unavailable, harness.service.receipt(REQUESTER, SecretRequestTestData.id(1)))
        assertEquals(0, harness.provider.writes)
        assertTrue(value.isWiped)
        assertNull(harness.provider.storedText(TARGET_REF))
    }

    /** A clock that follows the test scheduler's virtual time. */
    private class SchedulerClock(private val scope: TestScope) : TestClock() {
        override var nowMs: Long
            get() = SecretRequestTestData.NOW.toEpochMilliseconds() + scope.testScheduler.currentTime
            set(_) = error("The scheduler clock follows virtual time")
    }

    private class BrokenJournal : SecretRequestJournal {
        override suspend fun read(): List<SecretRequestRecord> = throw SecretRequestJournalException(SecretJournalProblem.CORRUPT)

        override suspend fun update(change: (List<SecretRequestRecord>) -> List<SecretRequestRecord>): List<SecretRequestRecord> =
            throw SecretRequestJournalException(SecretJournalProblem.CORRUPT)
    }
}
