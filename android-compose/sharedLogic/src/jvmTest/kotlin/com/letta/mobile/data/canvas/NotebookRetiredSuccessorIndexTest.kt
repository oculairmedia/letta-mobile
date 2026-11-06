package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.automerge.repo.DocumentId

/**
 * A retired storage id resolves through the persisted successor map, which compaction keeps
 * pointing at the current document; stores written before the map existed still resolve by scan.
 */
class NotebookRetiredSuccessorIndexTest {
    @Test
    fun repeatedCompactionKeepsEveryRetiredIdPointingAtTheCurrentDocument() {
        val directory = Files.createTempDirectory("notebook-successor-chain-")
        val target = seed(directory, others = 2)
        val first = compact(directory, target.stableKey(), 1_000L)
        val second = compact(directory, first, 2_000L)

        val successors = NotebookDocumentIndex(directory).successors()
        assertEquals(second, successors[target.stableKey()])
        assertEquals(second, successors[first])
        assertNull(successors[second])
        NotebookLocalStore(directory, PEER).use { store ->
            assertNull(store.open(target), "retired storage stays closed")
            assertEquals(second, store.resolveStorageId(target)?.stableKey())
            assertEquals("Target body", store.read(target)?.markdown)
            assertEquals(target.stableKey(), store.read(target)?.id?.value)
        }
    }

    @Test
    fun aStoreRetiredBeforeTheMapExistedStillResolvesByScan() = assertScanResolves { map, _ -> Files.delete(map) }

    @Test
    fun aRecordedSuccessorThatIsNoLongerIndexedFallsBackToTheScan() =
        assertScanResolves { map, retired -> Files.writeString(map, """{"$retired":"${"f".repeat(32)}"}""") }

    @Test
    fun aRecordedSuccessorThatDidNotReplaceTheIdFallsBackToTheScan() = assertScanResolves { map, retired ->
        val unrelated = NotebookDocumentIndex(map.parent).read().first { it != NotebookDocumentIndex(map.parent).successors()[retired] }
        Files.writeString(map, """{"$retired":"$unrelated"}""")
    }

    @Test
    fun anUnreadableSuccessorMapFallsBackToTheScan() = assertScanResolves { map, _ -> Files.writeString(map, "not json") }

    /** Retire one document, damage its map entry with [damage], and expect the scan to find the successor. */
    private fun assertScanResolves(damage: (map: Path, retired: String) -> Unit) {
        val directory = Files.createTempDirectory("notebook-successor-fallback-")
        val target = seed(directory, others = 2)
        val successor = compact(directory, target.stableKey(), 1_000L)
        damage(directory.resolve(NotebookDocumentIndex.SUCCESSORS_FILE), target.stableKey())

        NotebookLocalStore(directory, PEER).use { store ->
            assertEquals(successor, store.resolveStorageId(target)?.stableKey())
            assertEquals("Target body", store.read(target)?.markdown)
        }
    }

    /** Not a gate: prints map vs scan resolution time over a larger notebook set for the PR record. */
    @Test
    fun measureResolutionOverManyNotebooks() {
        val directory = Files.createTempDirectory("notebook-successor-measure-")
        val target = seed(directory, others = MEASURED_NOTEBOOKS)
        compact(directory, target.stableKey(), 1_000L)
        val mapped = NotebookLocalStore(directory, PEER).use { store -> timeResolution(store, target) }
        Files.delete(directory.resolve(NotebookDocumentIndex.SUCCESSORS_FILE))
        val scanned = NotebookLocalStore(directory, PEER).use { store -> timeResolution(store, target) }
        println("retired-id resolution over ${MEASURED_NOTEBOOKS + 1} notebooks: map ${mapped / 1_000} µs, scan ${scanned / 1_000} µs")
    }

    private fun timeResolution(store: NotebookLocalStore, target: DocumentId): Long {
        store.resolveStorageId(target) // warm the repository and index read
        return (1..RUNS).minOf { measureNanoTime { checkNotNull(store.resolveStorageId(target)) } }
    }

    private fun seed(directory: Path, others: Int): DocumentId = NotebookLocalStore(directory, PEER).use { store ->
        repeat(others) { n -> store.create("Other $n").also { store.persistCanvas(it) } }
        store.create("Target").also { id ->
            store.insertMarkdown(id, 0, "Target body")
            check(store.persistCanvas(id))
        }
    }

    private fun compact(directory: Path, key: String, now: Long): String =
        NotebookHistoryArchive(directory).compactToNewDocument(key, now, NotebookDocumentIndex(directory), 1_024L).newKey

    private fun DocumentId.stableKey(): String = getBytes().joinToString("") { "%02x".format(it) }

    private companion object {
        const val PEER = "successor-index-peer"
        const val MEASURED_NOTEBOOKS = 60
        const val RUNS = 5
    }
}
