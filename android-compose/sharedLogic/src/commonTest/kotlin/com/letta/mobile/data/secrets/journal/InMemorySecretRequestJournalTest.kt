package com.letta.mobile.data.secrets.journal

import com.letta.mobile.data.secrets.request.SecretFailureReason
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestStatus
import com.letta.mobile.data.secrets.request.SecretRequestTestData.id
import com.letta.mobile.data.secrets.request.SecretRequestTestData.record
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InMemorySecretRequestJournalTest {
    @Test
    fun updatesAreKeptAndRefusedUpdatesLeaveTheRecordsUnchanged() = runTest {
        val journal = InMemorySecretRequestJournal()
        journal.update { it + record(id(1)) }
        assertEquals(listOf(id(1)), journal.read().map { it.requestId })
        val refused = assertFailsWith<SecretRequestJournalException> { journal.update { it + record(id(1)) } }
        assertEquals(SecretJournalProblem.CORRUPT, refused.problem)
        assertEquals(listOf(id(1)), journal.read().map { it.requestId })
    }

    @Test
    fun theRulesBoundTheRecordCountAndRejectMalformedOrInconsistentRecords() {
        val full = (1..SecretRequestLimits.MAX_JOURNAL_RECORDS).map { record(id(it)) }
        assertNull(SecretRequestJournalRules.problem(full))
        assertEquals(SecretJournalProblem.FULL, SecretRequestJournalRules.problem(full + record(id(0))))
        assertEquals(SecretJournalProblem.CORRUPT, SecretRequestJournalRules.problem(listOf(record(SecretRequestId("sr_bad")))))
        val failedWithoutReason = record(status = SecretRequestStatus(SecretRequestState.FAILED, null, 1L))
        assertEquals(SecretJournalProblem.CORRUPT, SecretRequestJournalRules.problem(listOf(failedWithoutReason)))
        val savedWithoutTime = record(status = SecretRequestStatus(SecretRequestState.SAVED))
        assertEquals(SecretJournalProblem.CORRUPT, SecretRequestJournalRules.problem(listOf(savedWithoutTime)))
        val pendingWithReason = record(status = SecretRequestStatus(SecretRequestState.PENDING, SecretFailureReason.VAULT_ERROR))
        assertEquals(SecretJournalProblem.CORRUPT, SecretRequestJournalRules.problem(listOf(pendingWithReason)))
    }

    @Test
    fun journalErrorsCarryOnlyTheirFixedText() {
        SecretJournalProblem.entries.forEach { problem ->
            assertEquals(problem.message, SecretRequestJournalException(problem, IllegalStateException("cause text")).message)
        }
    }
}
