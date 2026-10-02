package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import org.automerge.AmValue
import org.automerge.ObjectId
import org.automerge.repo.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A crash can stop a move to a new document between any two of its steps. Whichever step it
 * stopped at, the next store to open the repository finds exactly one document holding the board
 * (the old one, or the new one with the old retired), openable, and no move debris left behind.
 */
class NotebookHistoryArchiveRecoveryTest {
    private class Crash : RuntimeException("simulated crash")

    private fun DocumentId.key(): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun notebook(path: Path): DocumentId = NotebookLocalStore(path, "recovery-peer").use { store ->
        store.create("Recovered").also { id -> store.insertMarkdown(id, 0, "Kept text") }
    }

    private fun previousDocument(store: NotebookLocalStore, id: DocumentId): String? = store.open(id)!!.withDocument { document ->
        (document.get(ObjectId.ROOT, NotebookHistoryArchive.PREVIOUS_DOCUMENT).orElse(null) as? AmValue.Str)?.value
    }.get(5, TimeUnit.SECONDS)

    private fun debris(path: Path, key: String): List<String> {
        val directory = NotebookHistoryArchive(path).archiveRoot.resolve(key)
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream -> stream.iterator().asSequence().map { it.name }.toList() }
            .filterNot { it.endsWith(".automerge.gz") }
    }

    /** Crash a move right after [step], then open the repository as the next launch would. */
    private fun crashAfter(step: NotebookHistoryArchive.Step, expectMoved: Boolean) {
        val path = Files.createTempDirectory("notebook-crash-${step.name.lowercase()}-")
        val id = notebook(path)
        val key = id.key()
        val archive = NotebookHistoryArchive(path)
        val crashing = NotebookHistoryArchive(path) { if (it == step) throw Crash() }
        assertFailsWith<Crash> { crashing.compactToNewDocument(key, System.currentTimeMillis(), NotebookDocumentIndex(path), 1024) }

        NotebookLocalStore(path, "recovery-peer").use { store ->
            val current = store.listDocuments().single()
            if (expectMoved) {
                assertNotEquals(id, current, "after $step")
                assertEquals(key, previousDocument(store, current), "after $step")
                assertNull(store.open(id), "after $step")
                assertFalse(Files.exists(archive.documentDirectory(key)), "after $step")
                assertTrue(key in NotebookDocumentIndex(path).retired(), "after $step")
            } else {
                assertEquals(id, current, "after $step")
                assertNull(previousDocument(store, id), "after $step")
                assertTrue(NotebookDocumentIndex(path).retired().isEmpty(), "after $step")
            }
            assertEquals("Kept text", store.read(current)!!.markdown)
            assertTrue(store.faults.faults.value.none { it.isError }, store.faults.faults.value.toString())
            // And it takes writes.
            store.insertMarkdown(current, 0, ">")
            assertEquals(">Kept text", store.read(current)!!.markdown)
        }
        assertEquals(emptyList(), debris(path, key), "after $step")
        // No orphaned new document is left in storage: only the indexed one (and nothing retired).
        val storageDirectories = Files.list(path).use { s -> s.iterator().asSequence().toList() }
            .filter { Files.isDirectory(it) && it.name.length == 2 }
            .sumOf { prefix -> Files.list(prefix).use { s -> s.filter { Files.isDirectory(it) }.count().toInt() } }
        assertEquals(1, storageDirectories, "after $step")
    }

    @Test
    fun aCrashAfterArchivingKeepsTheOldDocument() = crashAfter(NotebookHistoryArchive.Step.ARCHIVED, expectMoved = false)

    @Test
    fun aCrashWhileBuildingTheNewDocumentKeepsTheOldDocument() =
        crashAfter(NotebookHistoryArchive.Step.SUCCESSOR_PARTIAL, expectMoved = false)

    @Test
    fun aCrashAfterJournalingBeforeTheNewDocumentIsInPlaceKeepsTheOldDocument() =
        crashAfter(NotebookHistoryArchive.Step.JOURNALED, expectMoved = false)

    @Test
    fun aCrashWithTheNewDocumentWholeButNotIndexedFinishesTheMove() =
        crashAfter(NotebookHistoryArchive.Step.SUCCESSOR_READY, expectMoved = true)

    @Test
    fun aCrashAfterTheIndexSwapFinishesTheMove() = crashAfter(NotebookHistoryArchive.Step.INDEXED, expectMoved = true)

    @Test
    fun aCrashAfterRetiringBeforeDeletingTheOldStorageFinishesTheMove() =
        crashAfter(NotebookHistoryArchive.Step.RETIRED, expectMoved = true)

    @Test
    fun aCrashBeforeTheJournalIsRemovedFinishesTheMove() = crashAfter(NotebookHistoryArchive.Step.OLD_DELETED, expectMoved = true)

    /** Earlier builds restarted histories in place; a crash there left the old chunks moved aside. */
    @Test
    fun anEarlierBuildsRestartWithTheOldHistoryMovedAsideAndNoFreshDocumentPutsTheOldHistoryBack() {
        val path = Files.createTempDirectory("notebook-crash-no-fresh-")
        val id = notebook(path)
        val key = id.key()
        val archive = NotebookHistoryArchive(path)
        val directory = archive.documentDirectory(key)
        val staged = Files.createDirectories(archive.archiveRoot.resolve(key)).resolve("1.replaced")
        Files.move(directory, staged, ATOMIC_MOVE)

        NotebookLocalStore(path, "recovery-peer").use { store ->
            assertEquals("Kept text", store.read(id)!!.markdown)
        }
        assertEquals(emptyList(), debris(path, key))
    }

    /**
     * The state the earliest builds' restart left after a crash: old chunks moved aside and an
     * empty `snapshot/` directory created in their place.
     */
    @Test
    fun theEmptySnapshotDirectoryOlderBuildsLeftIsReplacedByTheOldHistory() {
        val path = Files.createTempDirectory("notebook-crash-legacy-")
        val id = notebook(path)
        val key = id.key()
        val archive = NotebookHistoryArchive(path)
        val directory = archive.documentDirectory(key)
        val archiveDirectory = Files.createDirectories(archive.archiveRoot.resolve(key))
        Files.move(directory, archiveDirectory.resolve("1.replaced"), ATOMIC_MOVE)
        Files.createDirectories(directory.resolve("snapshot"))
        Files.createTempFile(archiveDirectory, ".snapshot-", ".tmp")

        NotebookLocalStore(path, "recovery-peer").use { store ->
            assertEquals(listOf(id), store.listDocuments())
            assertEquals("Kept text", store.read(id)!!.markdown)
            assertTrue(store.faults.faults.value.none { it.isError }, store.faults.faults.value.toString())
        }
        assertEquals(emptyList(), debris(path, key))
    }
}
