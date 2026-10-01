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
import kotlin.test.assertTrue

/**
 * A crash can stop a history restart between any two of its steps. Whichever step it stopped at,
 * the next store to open the repository finds the document whole (old history or fresh restart),
 * openable, and with no restart debris left behind.
 */
class NotebookHistoryArchiveRecoveryTest {
    private class Crash : RuntimeException("simulated crash")

    private fun DocumentId.key(): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun notebook(path: Path): DocumentId = NotebookLocalStore(path, "recovery-peer").use { store ->
        store.create("Recovered").also { id -> store.insertMarkdown(id, 0, "Kept text") }
    }

    /** Whether the document continues from a restart (it names its archive) rather than its old history. */
    private fun restarted(store: NotebookLocalStore, id: DocumentId): Boolean = store.open(id)!!.withDocument { document ->
        document.get(ObjectId.ROOT, NotebookHistoryArchive.HISTORY_ARCHIVE).orElse(null) is AmValue.List
    }.get(5, TimeUnit.SECONDS)

    private fun debris(path: Path, key: String): List<String> {
        val directory = NotebookHistoryArchive(path).archiveRoot.resolve(key)
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream -> stream.iterator().asSequence().map { it.name }.toList() }
            .filterNot { it.endsWith(".automerge.gz") }
    }

    /** Crash a restart right after [step], then open the repository as the next launch would. */
    private fun crashAfter(step: NotebookHistoryArchive.Step, expectRestarted: Boolean) {
        val path = Files.createTempDirectory("notebook-crash-${step.name.lowercase()}-")
        val id = notebook(path)
        val key = id.key()
        val crashing = NotebookHistoryArchive(path) { if (it == step) throw Crash() }
        assertFailsWith<Crash> { crashing.compact(key, System.currentTimeMillis()) }

        NotebookLocalStore(path, "recovery-peer").use { store ->
            assertEquals(listOf(id), store.listDocuments())
            assertEquals("Kept text", store.read(id)!!.markdown)
            assertEquals(expectRestarted, restarted(store, id), "after $step")
            assertTrue(store.faults.faults.value.none { it.isError }, store.faults.faults.value.toString())
            // And it takes writes.
            store.insertMarkdown(id, 0, ">")
            assertEquals(">Kept text", store.read(id)!!.markdown)
        }
        assertEquals(emptyList(), debris(path, key), "after $step")
    }

    @Test
    fun aCrashAfterArchivingKeepsTheOldHistory() = crashAfter(NotebookHistoryArchive.Step.ARCHIVED, expectRestarted = false)

    @Test
    fun aCrashWhileBuildingTheFreshDocumentKeepsTheOldHistory() =
        crashAfter(NotebookHistoryArchive.Step.FRESH_PARTIAL, expectRestarted = false)

    @Test
    fun aCrashBeforeTheOldHistoryMovesKeepsIt() = crashAfter(NotebookHistoryArchive.Step.FRESH_READY, expectRestarted = false)

    @Test
    fun aCrashWithTheOldHistoryMovedAsideFinishesTheRestart() =
        crashAfter(NotebookHistoryArchive.Step.MOVED_ASIDE, expectRestarted = true)

    @Test
    fun aCrashBeforeCleanupKeepsTheRestart() = crashAfter(NotebookHistoryArchive.Step.SWAPPED, expectRestarted = true)

    @Test
    fun aCrashWithTheOldHistoryMovedAsideAndNoFreshDocumentPutsTheOldHistoryBack() {
        val path = Files.createTempDirectory("notebook-crash-no-fresh-")
        val id = notebook(path)
        val key = id.key()
        val archive = NotebookHistoryArchive(path)
        val directory = archive.documentDirectory(key)
        val staged = Files.createDirectories(archive.archiveRoot.resolve(key)).resolve("1.replaced")
        Files.move(directory, staged, ATOMIC_MOVE)

        NotebookLocalStore(path, "recovery-peer").use { store ->
            assertEquals("Kept text", store.read(id)!!.markdown)
            assertTrue(!restarted(store, id))
        }
        assertEquals(emptyList(), debris(path, key))
    }

    /**
     * The state older builds of this restart left after a crash: old chunks moved aside and an
     * empty `snapshot/` directory created in their place. Recovery used to fail deleting the
     * non-empty document directory, leaving the document unopenable for good.
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
