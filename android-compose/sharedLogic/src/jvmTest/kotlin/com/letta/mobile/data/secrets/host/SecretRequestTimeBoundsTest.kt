package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.request.SecretFailureReason
import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.plugin.keystore.SecretValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * One hung keystore cannot block every submission: a write that runs over the keystore deadline
 * settles as failed and frees the write lock, and a submission that cannot get the lock in time is
 * refused before its value is decoded or its request claimed.
 */
class SecretRequestTimeBoundsTest {
    private fun bounded(deadline: Duration, lockWait: Duration): ServiceHarness {
        val clock = TestClock()
        val random = CountingRandom()
        val settings = testSettings(clock, random).copy(keystoreDeadline = deadline, writeLockWait = lockWait)
        return ServiceHarness(clock = clock, random = random, settings = settings)
    }

    private suspend fun ServiceHarness.stateOf(id: SecretRequestId): SecretRequestState? =
        journal.read().firstOrNull { it.requestId == id }?.status?.state

    @Test
    fun theDefaultsBoundTheWriteAndTheWaitForTheLock() {
        assertEquals(15.seconds, SecretRequestSettings().keystoreDeadline)
        assertEquals(2.seconds, SecretRequestSettings().writeLockWait)
    }

    @Test
    fun aHungWriteSettlesAsVaultUnavailableWipesTheValueAndFreesTheLock() = runTest {
        val harness = bounded(deadline = 15.seconds, lockWait = 1.hours)
        val hung = harness.pending()
        val next = harness.pending()
        harness.provider.putGate = CompletableDeferred()
        val value = secretOf("sentinel-hung-write")

        val answer = harness.service.submit(HUMAN, hung, value) as SecretServiceResult.Ok

        assertEquals(SecretReceiptStatus.FAILED, answer.value.status)
        assertEquals(SecretFailureReason.VAULT_UNAVAILABLE, answer.value.reason)
        assertTrue(value.isWiped)
        assertEquals(SecretRequestState.FAILED, harness.stateOf(hung), "never left submitting")
        assertEquals(0, harness.provider.writes)
        harness.provider.putGate = null
        val saved = harness.service.submit(HUMAN, next, secretOf("after-the-hang")) as SecretServiceResult.Ok
        assertEquals(SecretReceiptStatus.SAVED, saved.value.status, "the lock was released")
    }

    @Test
    fun aSubmissionThatCannotGetTheLockIsRefusedBusyBeforeDecodingOrClaiming() = runTest {
        val harness = bounded(deadline = 1.hours, lockWait = 1.seconds)
        val first = harness.pending()
        val second = harness.pending()
        val gate = CompletableDeferred<Unit>().also { harness.provider.putGate = it }
        val writing = async { harness.service.submit(HUMAN, first, secretOf("first-value")) }
        harness.provider.putStarted.await()
        var decodes = 0

        val busy = harness.service.submit(HUMAN, second) {
            decodes++
            secretOf("second-value")
        }

        assertEquals(SecretServiceResult.Refused(SecretServiceError.BUSY), busy)
        assertEquals(0, decodes, "nothing was decoded")
        assertEquals(SecretRequestState.PENDING, harness.stateOf(second), "nothing was claimed")
        gate.complete(Unit)
        assertEquals(SecretReceiptStatus.SAVED, (writing.await() as SecretServiceResult.Ok).value.status)
        // The human can submit again; the slot now holds the first value, which this request (made
        // while the slot was empty) may not replace.
        val retried = harness.service.submit(HUMAN, second, secretOf("second-value")) as SecretServiceResult.Ok
        assertEquals(SecretFailureReason.TARGET_CHANGED, retried.value.reason)
        assertEquals("first-value", harness.provider.storedText(TARGET_REF))
    }

    @Test
    fun aSubmissionForARequestThatIsNotPendingIsRefusedWithoutWaitingOrDecoding() = runTest {
        val harness = bounded(deadline = 1.hours, lockWait = 1.hours)
        val id = harness.pending()
        harness.provider.putGate = CompletableDeferred()
        val writing = async { harness.service.submit(HUMAN, id, secretOf("in-flight")) }
        harness.provider.putStarted.await()
        var decodes = 0

        val refused = harness.service.submit(HUMAN, id) {
            decodes++
            secretOf("replay")
        }

        assertEquals(SecretServiceResult.Refused(SecretServiceError.NOT_PENDING), refused)
        assertEquals(0, decodes)
        writing.cancel()
    }

    @Test
    fun aHungCheckOfTheSlotAtCreationCountsAsNoExistingValue() = runTest {
        val harness = bounded(deadline = 15.seconds, lockWait = 1.seconds)
        harness.provider.seed(TARGET_REF, "owner-value")
        harness.provider.getGate = CompletableDeferred()

        val id = harness.pending()

        harness.provider.getGate = null
        val record = (harness.service.get(HUMAN, id) as SecretServiceResult.Ok).value
        assertFalse(record.details.replacesExisting, "the safe side: this request may never replace a value")
        assertTrue(harness.provider.lent.all(SecretValue::isWiped))
    }
}
