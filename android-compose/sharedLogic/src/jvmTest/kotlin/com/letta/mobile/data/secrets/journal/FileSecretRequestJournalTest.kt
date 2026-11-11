package com.letta.mobile.data.secrets.journal

import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestTestData
import com.letta.mobile.data.secrets.request.SecretRequestTestData.id
import com.letta.mobile.data.secrets.request.SecretRequestTestData.record
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSecretRequestJournalTest {
    private val directory: Path = Files.createTempDirectory("secret-request-journal")
    private val file: Path = directory.resolve("requests.json")
    private val barrier: Path = directory.resolve("requests.json.pending")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private suspend fun corruptProblem(journal: SecretRequestJournal = FileSecretRequestJournal(file)): SecretJournalProblem =
        assertFailsWith<SecretRequestJournalException> { journal.read() }.problem

    @Test
    fun recordsSurviveARestart() = runTest {
        FileSecretRequestJournal(file).update { it + record(id(1)) + record(id(2), SecretRequestTestData.status(SecretRequestState.SAVED)) }
        val reopened = FileSecretRequestJournal(file).read()
        assertEquals(listOf(record(id(1)), record(id(2), SecretRequestTestData.status(SecretRequestState.SAVED))), reopened)
        assertFalse(Files.exists(barrier))
    }

    @Test
    fun aMissingJournalReadsEmptyAndAnEmptyOrGarbageOneFailsClosedWithoutBeingReset() = runTest {
        assertEquals(emptyList(), FileSecretRequestJournal(file).read())
        val corruptions = listOf(
            "",
            "not json",
            """{"version":2,"records":[]}""",
            """{"version":1,"version":1,"records":[]}""",
            """{"version":1,"records":[{"requestId":"sr_bad"}]}""",
        )
        for (text in corruptions) {
            Files.writeString(file, text)
            val before = Files.readAllBytes(file)
            assertEquals(SecretJournalProblem.CORRUPT, corruptProblem(), text)
            val refused = assertFailsWith<SecretRequestJournalException> { FileSecretRequestJournal(file).update { it + record(id(3)) } }
            assertEquals(SecretJournalProblem.CORRUPT, refused.problem)
            assertContentEquals(before, Files.readAllBytes(file), "a corrupt journal is never rewritten")
        }
    }

    @Test
    fun invalidUtf8AnOversizedFileAndADirectoryFailClosed() = runTest {
        Files.write(file, byteArrayOf(0x7b, 0xff.toByte(), 0x7d))
        assertEquals(SecretJournalProblem.CORRUPT, corruptProblem())
        Files.write(file, ByteArray(FileSecretRequestJournal.MAX_JOURNAL_BYTES + 1) { ' '.code.toByte() })
        assertEquals(SecretJournalProblem.CORRUPT, corruptProblem())
        Files.delete(file)
        Files.createDirectory(file)
        assertEquals(SecretJournalProblem.CORRUPT, corruptProblem())
    }

    @Test
    fun recordsBreakingTheRulesAreRefusedOnWriteAndOnRead() = runTest {
        val journal = FileSecretRequestJournal(file)
        journal.update { it + record(id(1)) }
        val duplicate = assertFailsWith<SecretRequestJournalException> { journal.update { it + record(id(1)) } }
        assertEquals(SecretJournalProblem.CORRUPT, duplicate.problem)
        val overfull = (1..SecretRequestLimits.MAX_JOURNAL_RECORDS + 1).map { record(id(it)) }
        assertEquals(SecretJournalProblem.FULL, assertFailsWith<SecretRequestJournalException> { journal.update { overfull } }.problem)
        assertEquals(listOf(id(1)), journal.read().map { it.requestId })
    }

    @Test
    fun aLeftoverBarrierBlocksEveryOperationUntilReconciled() = runTest {
        FileSecretRequestJournal(file).update { it + record(id(1)) }
        Files.writeString(barrier, "left by a crash")
        assertEquals(SecretJournalProblem.NEEDS_RECONCILIATION, corruptProblem())
        val refused = assertFailsWith<SecretRequestJournalException> { FileSecretRequestJournal(file).update { emptyList() } }
        assertEquals(SecretJournalProblem.NEEDS_RECONCILIATION, refused.problem)
        Files.delete(barrier)
        assertEquals(listOf(id(1)), FileSecretRequestJournal(file).read().map { it.requestId })
    }

    @Test
    fun aFailedReplacementRollsBackAndKeepsThePreviousRecords() = runTest {
        FileSecretRequestJournal(file).update { it + record(id(1)) }
        val before = Files.readAllBytes(file)
        var syncs = 0
        val failingOnce = FileSecretRequestJournal(file) { syncs++; if (syncs == 3) throw java.io.IOException("sync failed") }
        val failure = assertFailsWith<SecretRequestJournalException> { failingOnce.update { it + record(id(2)) } }
        assertEquals(SecretJournalProblem.WRITE_FAILED, failure.problem)
        assertContentEquals(before, Files.readAllBytes(file))
        assertFalse(Files.exists(barrier))
        assertEquals(listOf(id(1)), failingOnce.read().map { it.requestId })
    }

    @Test
    fun aFailedRollbackLeavesTheBarrierAndFailsClosed() = runTest {
        FileSecretRequestJournal(file).update { it + record(id(1)) }
        var syncs = 0
        val failing = FileSecretRequestJournal(file) { syncs++; if (syncs >= 3) throw java.io.IOException("sync failed") }
        assertEquals(SecretJournalProblem.WRITE_FAILED, assertFailsWith<SecretRequestJournalException> { failing.update { it } }.problem)
        assertTrue(Files.exists(barrier))
        assertEquals(SecretJournalProblem.NEEDS_RECONCILIATION, corruptProblem(failing))
        assertEquals(SecretJournalProblem.NEEDS_RECONCILIATION, corruptProblem())
    }
}
