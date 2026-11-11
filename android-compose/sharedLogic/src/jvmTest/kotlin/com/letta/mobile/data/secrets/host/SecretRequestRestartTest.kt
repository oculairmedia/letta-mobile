package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.journal.FileSecretRequestJournal
import com.letta.mobile.data.secrets.request.SecretFailureReason
import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestReceipt
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestStatus
import com.letta.mobile.data.secrets.request.SecretRequestTestData.TARGET
import com.letta.mobile.data.secrets.request.SecretServiceError
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecretRequestRestartTest {
    private val directory: Path = Files.createTempDirectory("secret-request-restart")
    private val file: Path = directory.resolve("requests.json")
    private val provider = TestKeystoreProvider()
    private val clock = TestClock()
    private val random = CountingRandom()
    private val notPending = SecretServiceResult.Refused(SecretServiceError.NOT_PENDING)

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    /** A fresh service over a fresh journal instance on the same file: a host restart. */
    private fun restart(): ServiceHarness = ServiceHarness(FileSecretRequestJournal(file), provider, clock, random)

    @Test
    fun aPendingRequestSurvivesARestartAndCanStillBeAnsweredOnce() = runTest {
        val id = restart().pending()
        val saved = SecretRequestReceipt(SecretReceiptStatus.SAVED, TARGET.item, TARGET.field)
        assertEquals(SecretServiceResult.Ok(saved), restart().service.submit(HUMAN, id, secretOf("after-restart")))
        assertEquals(notPending, restart().service.submit(HUMAN, id, secretOf("replay")))
        assertEquals(1, provider.writes)
    }

    @Test
    fun aReplayAfterARestartIsRefused() = runTest {
        val first = restart()
        val id = first.pending()
        first.service.submit(HUMAN, id, secretOf("original"))
        val replay = secretOf("replayed")
        assertEquals(notPending, restart().service.submit(HUMAN, id, replay))
        assertEquals("original", provider.storedText(TARGET_REF))
        assertTrue(replay.isWiped)
    }

    @Test
    fun expiryIsFixedAcrossARestart() = runTest {
        val id = restart().pending()
        clock.nowMs += SecretRequestLimits.DEFAULT_TTL_MS
        assertEquals(notPending, restart().service.submit(HUMAN, id, secretOf("late")))
        val receipt = restart().service.receipt(REQUESTER, id) as SecretServiceResult.Ok
        assertEquals(SecretReceiptStatus.EXPIRED, receipt.value?.status)
        assertEquals(0, provider.writes)
    }

    @Test
    fun aWriteInterruptedByARestartSettlesAsInterruptedAndCannotBeRetried() = runTest {
        val id = restart().pending()
        FileSecretRequestJournal(file).update { records ->
            records.map { it.copy(status = SecretRequestStatus(SecretRequestState.SUBMITTING)) }
        }
        val after = restart()
        val receipt = after.service.receipt(REQUESTER, id) as SecretServiceResult.Ok
        assertEquals(SecretReceiptStatus.FAILED, receipt.value?.status)
        assertEquals(SecretFailureReason.INTERRUPTED, receipt.value?.reason)
        assertEquals(notPending, after.service.submit(HUMAN, id, secretOf("retry")))
        assertEquals(SecretRequestState.FAILED, FileSecretRequestJournal(file).read().single().status.state)
    }

    @Test
    fun settledRequestsArePrunedFromTheFileAfterTheRetentionWindow() = runTest {
        val harness = restart()
        val answered = harness.pending()
        harness.service.cancel(HUMAN, answered)
        clock.nowMs += SecretRequestLimits.SETTLED_RETENTION_MS + 1
        val fresh = restart().pending()
        assertEquals(listOf(fresh), FileSecretRequestJournal(file).read().map { it.requestId })
        assertEquals(notPending, restart().service.submit(HUMAN, answered, secretOf("pruned")))
        assertEquals(0, provider.writes)
    }
}
