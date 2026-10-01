package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.io.path.name
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.ObjectId
import org.automerge.repo.DocumentId
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Storage failures are non-fatal but loud; over-budget histories are archived, not lost. */
class NotebookStorageFaultsTest {
    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(20)
        }
    }

    private fun bytesUnder(path: Path): Long = Files.walk(path).use { stream ->
        stream.iterator().asSequence().filter { Files.isRegularFile(it) }.sumOf { Files.size(it) }
    }

    private fun DocumentId.key(): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private val bloatedScene = """{"bgColor":"#ffffffff","elements":[{"id":"a","type":"Path","points":["1,2"]}]}"""

    /** A canvas whose history is bloated the way the old storage did it: one large string rewritten many times. */
    private fun bloatedCanvas(path: Path, canvasId: CanvasId): DocumentId = runBlocking {
        NotebookLocalStore(path, "budget-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            store.upsert(CanvasDocument(canvasId, title = "Bloated", sceneJson = bloatedScene))
            val id = notebooks.listDocuments().single()
            notebooks.insertMarkdown(id, 0, "Kept text")
            repeat(60) { round ->
                notebooks.open(id)!!.withDocument { document ->
                    document.startTransaction().use { tx ->
                        val random = kotlin.random.Random(round)
                        tx.set(ObjectId.ROOT, "scratch", (0 until 2_000).joinToString(",") { "${random.nextLong()}" })
                        tx.commit()
                    }
                }.get(5, TimeUnit.SECONDS)
            }
            id
        }
    }

    private val smallBudget = NotebookHistoryBudget(maxDocumentBytes = 256 * 1024)

    @Test
    fun documentStorageLivesWhereTheArchiveLooksForIt() {
        val path = Files.createTempDirectory("notebook-layout-")
        val id = NotebookLocalStore(path, "layout-peer").use { it.create("Layout") }
        val directory = NotebookHistoryArchive(path).documentDirectory(id.key())
        assertTrue(Files.isDirectory(directory), "expected $directory")
        assertTrue(NotebookHistoryArchive(path).documentBytes(id.key()) > 0)
    }

    @Test
    fun aFailedSaveIsRecordedAndObservableAndTheStoreKeepsWorking() = runBlocking {
        val path = Files.createTempDirectory("notebook-failing-storage-")
        var failPuts = false
        val failing = { delegate: Storage ->
            object : Storage by delegate {
                override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> {
                    if (failPuts) throw OutOfMemoryError("Java heap space (injected)")
                    return delegate.put(key, value)
                }
            }
        }
        NotebookLocalStore(path, "failing-peer", storage = failing).use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val canvasId = CanvasId("failing")
            store.upsert(CanvasDocument(canvasId, title = "Failing", sceneJson = """{"elements":[]}"""))
            failPuts = true
            store.upsert(CanvasDocument(canvasId, title = "Failing", revision = 1, sceneJson = """{"elements":[{"id":"a"}]}"""))
            waitFor { store.storageFaults.value.any { it.kind == CanvasStorageFault.Kind.SAVE_FAILED } }
            val fault = store.storageFaults.value.first { it.kind == CanvasStorageFault.Kind.SAVE_FAILED }
            assertEquals(canvasId, fault.canvasId)
            assertNotNull(fault.documentId)
            assertNotNull(fault.documentBytes)
            assertTrue(fault.isError)
            // The process and the store carry on; the board is still readable and writable.
            val session = CanvasSession(canvasId, store)
            assertEquals(listOf(fault), session.storageFaults.value.affecting(canvasId).filter { it.kind == fault.kind })
            store.upsert(CanvasDocument(canvasId, title = "Failing", revision = 2, sceneJson = """{"elements":[{"id":"b"}]}"""))
            assertTrue(store.get(canvasId)!!.sceneJson.contains("\"b\""))
        }
    }

    @Test
    fun anOutOfMemoryErrorOnARepositoryThreadIsAbsorbedAndTheStoreTurnsReadOnly() = runBlocking {
        val path = Files.createTempDirectory("notebook-guard-")
        NotebookLocalStore(path, "guard-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val canvasId = CanvasId("guarded")
            store.upsert(CanvasDocument(canvasId, title = "Guarded", sceneJson = """{"elements":[{"id":"a"}]}"""))
            val id = notebooks.listDocuments().single()
            val oom = OutOfMemoryError("Failed to allocate a 62600352 byte allocation (simulated)").apply {
                stackTrace = arrayOf(
                    StackTraceElement("java.lang.Object", "clone", null, -2),
                    StackTraceElement("org.automerge.repo.StorageTask\$Put", "<init>", "StorageTask.java", 93),
                    StackTraceElement("org.automerge.repo.RepoRuntime", "routeIoResultToActor", "RepoRuntime.java", 472),
                )
            }
            // Exactly where the phone died: thrown out of a task on the repository's own work-stealing pool.
            val pool = assertNotNull(NotebookStorageHealth.documentPoolOf(notebooks.repo))
            pool.execute { throw oom }
            waitFor { notebooks.isReadOnly }
            val faults = notebooks.faults.faults.value
            val saveFailed = faults.single { it.kind == CanvasStorageFault.Kind.SAVE_FAILED }
            assertNotNull(saveFailed.documentId)
            assertTrue(saveFailed.message.contains("ForkJoinPool"), saveFailed.message)
            // Degraded, visibly: a READ_ONLY fault every board shows, and writes are refused.
            val readOnly = faults.single { it.kind == CanvasStorageFault.Kind.READ_ONLY }
            assertTrue(readOnly.isError)
            assertTrue(readOnly in faults.affecting(canvasId))
            assertFailsWith<NotebookReadOnlyException> { notebooks.insertMarkdown(id, 0, "lost") }
            // The canvas store does not throw at the board: the session keeps the edit in memory.
            store.upsert(CanvasDocument(canvasId, title = "Guarded", revision = 1, sceneJson = """{"elements":[{"id":"b"}]}"""))
            assertFalse(store.upsertIfRevision(CanvasDocument(canvasId, title = "Guarded", revision = 2, sceneJson = "{\"elements\":[]}"), 0))
            // Reads still work, and show what is on disk.
            assertTrue(store.get(canvasId)!!.sceneJson.contains("\"a\""))
        }
    }

    @Test
    fun onlyErrorsOnTheRepositorysOwnThreadsAreAbsorbed() {
        val path = Files.createTempDirectory("notebook-guard-scope-")
        NotebookLocalStore(path, "guard-scope-peer").use { notebooks ->
            notebooks.create("Scoped")
            val pool = assertNotNull(NotebookStorageHealth.documentPoolOf(notebooks.repo))
            val repositoryThread = CompletableFuture.supplyAsync({ Thread.currentThread() }, pool).get(5, TimeUnit.SECONDS)
            val automergeFrames = arrayOf(
                StackTraceElement("org.automerge.repo.DocumentActor", "handle", "DocumentActor.java", 1),
                StackTraceElement("org.automerge.repo.RepoRuntime", "withDocument", "RepoRuntime.java", 2),
            )
            // An app exception raised inside a withDocument block, with Automerge all over its stack.
            val appBug = IllegalStateException("Notebook belongs to another canvas").apply { stackTrace = automergeFrames }
            assertFalse(AutomergeFaultGuard.handles(repositoryThread, appBug))
            assertFalse(AutomergeFaultGuard.handles(Thread("automerge-hub"), appBug))
            // Errors: on the repository's threads, yes; on the main thread or someone else's, no.
            val oom = OutOfMemoryError("simulated").apply { stackTrace = automergeFrames }
            assertTrue(AutomergeFaultGuard.handles(repositoryThread, OutOfMemoryError("app code")))
            assertTrue(AutomergeFaultGuard.handles(Thread("automerge-io"), OutOfMemoryError("app code")))
            assertFalse(AutomergeFaultGuard.handles(Thread("main"), oom))
            assertFalse(AutomergeFaultGuard.handles(Thread("worker"), OutOfMemoryError("app code")))
            // ...unless Automerge itself raised it.
            assertTrue(AutomergeFaultGuard.handles(Thread("worker"), oom))

            // Thrown for real on the repository's pool, the app exception is not taken for a storage fault.
            pool.execute { throw appBug }
            Thread.sleep(300)
            assertTrue(notebooks.faults.faults.value.none { it.isError }, notebooks.faults.faults.value.toString())
            assertFalse(notebooks.isReadOnly)
        }
    }

    @Test
    fun anOverBudgetHistoryIsArchivedAndTheDocumentContinuesFromItsCurrentState() = runBlocking {
        val path = Files.createTempDirectory("notebook-budget-")
        val canvasId = CanvasId("bloated")
        val budget = smallBudget.copy(compactOversized = true)
        val id = bloatedCanvas(path, canvasId)
        val key = id.key()
        val archive = NotebookHistoryArchive(path)
        val before = archive.documentBytes(key)
        assertTrue(before > budget.maxDocumentBytes, "history only reached $before bytes")

        NotebookLocalStore(path, "budget-peer", budget).use { notebooks ->
            // Restarting runs in the background; listing waits for it, as every open does.
            notebooks.listDocuments()
            val compacted = notebooks.faults.faults.value.single { it.kind == CanvasStorageFault.Kind.COMPACTED }
            assertEquals(key, compacted.documentId)
            assertTrue(archive.documentBytes(key) < before / 4, "restarted at ${archive.documentBytes(key)} of $before")
            // Same document id, same canvas, same content.
            assertEquals(listOf(id), notebooks.listDocuments())
            val store = NotebookCanvasDocumentStore(notebooks)
            val doc = store.get(canvasId)!!
            assertEquals(Json.parseToJsonElement(bloatedScene), Json.parseToJsonElement(doc.sceneJson))
            assertEquals("Kept text", notebooks.read(id)!!.markdown)
            // The restarted document names its archive, and the archive holds the full history.
            val entry = notebooks.open(id)!!.withDocument { document ->
                val list = (document.get(ObjectId.ROOT, NotebookHistoryArchive.HISTORY_ARCHIVE).orElseThrow() as AmValue.List).id
                (document.listItems(list).orElseThrow().single() as AmValue.Str).value
            }.get(5, TimeUnit.SECONDS)
            val file = Json.parseToJsonElement(entry).jsonObject.getValue("file").jsonPrimitive.content
            val archived = archive.archiveRoot.resolve(key).resolve(file)
            val history = GZIPInputStream(Files.newInputStream(archived)).use { it.readBytes() }
            assertEquals(before, history.size.toLong())
            val old = Document.load(history)
            try {
                assertTrue(old.getHeads().isNotEmpty())
                assertEquals("Kept text", old.text((old.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id).orElseThrow())
            } finally {
                old.free()
            }
            // And it keeps working.
            assertTrue(store.upsertIfRevision(doc.copy(revision = doc.revision + 1, sceneJson = bloatedScene.replace("1,2", "5,6")), doc.revision))
        }
        NotebookLocalStore(path, "budget-peer", budget).use { notebooks ->
            assertTrue(NotebookCanvasDocumentStore(notebooks).get(canvasId)!!.sceneJson.contains("5,6"))
            assertTrue(notebooks.faults.faults.value.none { it.isError })
            assertEquals(1, Files.list(archive.archiveRoot.resolve(key)).use { s -> s.iterator().asSequence().count { it.name.endsWith(".gz") } })
        }
    }

    @Test
    fun byDefaultAnOverBudgetHistoryIsReportedLoudlyAndNeverRestarted() = runBlocking {
        val path = Files.createTempDirectory("notebook-budget-default-")
        val canvasId = CanvasId("synced")
        val id = bloatedCanvas(path, canvasId)
        NotebookLocalStore(path, "budget-peer", smallBudget).use { notebooks ->
            val doc = NotebookCanvasDocumentStore(notebooks).get(canvasId)!!
            val over = notebooks.faults.faults.value.single { it.kind == CanvasStorageFault.Kind.OVER_BUDGET }
            // An error, so the board shows it, not a log line.
            assertTrue(over.isError)
            assertEquals(id.key(), over.documentId)
            assertTrue(over in notebooks.faults.faults.value.affecting(canvasId))
            assertTrue(over.message.contains("may sync with peers"), over.message)
            assertTrue(notebooks.faults.faults.value.none { it.kind == CanvasStorageFault.Kind.COMPACTED })
            assertEquals(Json.parseToJsonElement(bloatedScene), Json.parseToJsonElement(doc.sceneJson))
        }
        assertNotRestarted(path, id)
    }

    /** The full history is still live (no archive, no restart entry, still over budget). */
    private fun assertNotRestarted(path: Path, id: DocumentId) {
        val archive = NotebookHistoryArchive(path)
        assertFalse(Files.exists(archive.archiveRoot.resolve(id.key())))
        assertTrue(archive.documentBytes(id.key()) > smallBudget.maxDocumentBytes)
        NotebookLocalStore(path, "budget-peer").use { notebooks ->
            val entry = notebooks.open(id)!!.withDocument { document ->
                document.get(ObjectId.ROOT, NotebookHistoryArchive.HISTORY_ARCHIVE).orElse(null)
            }.get(5, TimeUnit.SECONDS)
            assertEquals(null, entry)
        }
    }

    @Test
    fun aRepositoryThatEverSyncedIsNeverRestartedEvenWhenAStoreOptsIn() = runBlocking {
        val path = Files.createTempDirectory("notebook-budget-synced-")
        val id = bloatedCanvas(path, CanvasId("synced"))
        NotebookLocalStore(path, "budget-peer").use { it.repoForSync() }
        NotebookLocalStore(path, "budget-peer", smallBudget.copy(compactOversized = true)).use { notebooks ->
            notebooks.listDocuments()
            val over = notebooks.faults.faults.value.single { it.kind == CanvasStorageFault.Kind.OVER_BUDGET }
            assertTrue(over.message.contains("has synced with peers"), over.message)
            assertTrue(notebooks.faults.faults.value.none { it.kind == CanvasStorageFault.Kind.COMPACTED })
            // And this store, having opted in, may not be handed to sync itself.
            assertFailsWith<IllegalStateException> { notebooks.repoForSync() }
        }
        assertNotRestarted(path, id)
    }

    @Test
    fun twoStoresOpeningAtOnceRestartADocumentOnlyOnce() = runBlocking {
        val path = Files.createTempDirectory("notebook-budget-race-")
        val id = bloatedCanvas(path, CanvasId("raced"))
        val budget = smallBudget.copy(compactOversized = true)
        val first = NotebookLocalStore(path, "budget-peer", budget)
        val second = NotebookLocalStore(path, "budget-peer", budget)
        try {
            first.listDocuments()
            second.listDocuments()
            val compactions = (first.faults.faults.value + second.faults.faults.value).count { it.kind == CanvasStorageFault.Kind.COMPACTED }
            // The second re-measures under the lock and finds the restarted, small history.
            assertEquals(1, compactions)
            val archives = Files.list(NotebookHistoryArchive(path).archiveRoot.resolve(id.key())).use { s ->
                s.iterator().asSequence().map { it.name }.toList()
            }
            assertEquals(1, archives.count { it.endsWith(".gz") }, archives.toString())
            assertEquals(archives.size, archives.count { it.endsWith(".gz") }, archives.toString())
        } finally {
            first.close()
            second.close()
        }
        NotebookLocalStore(path, "budget-peer").use { notebooks ->
            assertEquals("Kept text", notebooks.read(id)!!.markdown)
        }
    }

    @Test
    fun aDocumentTooLargeToRestartIsSetAsideNotOpened() {
        val path = Files.createTempDirectory("notebook-quarantine-")
        val id = NotebookLocalStore(path, "quarantine-peer").use { it.create("Broken") }
        val key = id.key()
        val directory = NotebookHistoryArchive(path).documentDirectory(key)
        // A chunk that is not Automerge and far over the budget: it cannot be archived and restarted.
        Files.write(Files.createDirectories(directory.resolve("snapshot")).resolve("ff".repeat(32)), ByteArray(64 * 1024) { 7 })
        val size = bytesUnder(directory)
        val budget = NotebookHistoryBudget(maxDocumentBytes = 8 * 1024, compactOversized = true)
        NotebookLocalStore(path, "quarantine-peer", budget).use { notebooks ->
            notebooks.listDocuments()
            val fault = notebooks.faults.faults.value.single { it.kind == CanvasStorageFault.Kind.QUARANTINED }
            assertEquals(key, fault.documentId)
            assertEquals(size, fault.documentBytes)
            assertTrue(notebooks.listDocuments().isEmpty())
            assertEquals(null, notebooks.open(id))
        }
        // The files are untouched for manual recovery, and the failed restart left nothing behind.
        assertEquals(size, bytesUnder(directory))
        val leftovers = NotebookHistoryArchive(path).archiveRoot.resolve(key).let { dir ->
            if (!Files.isDirectory(dir)) emptyList() else Files.list(dir).use { s -> s.iterator().asSequence().map { it.name }.toList() }
        }
        assertTrue(leftovers.none { it.endsWith(".fresh") || it.endsWith(".replaced") || it.endsWith(".building") }, leftovers.toString())
    }

    @Test
    fun approachingTheBudgetIsAWarningNotAnError() {
        val path = Files.createTempDirectory("notebook-near-budget-")
        NotebookLocalStore(path, "near-peer").use { it.create("Near") }
        NotebookLocalStore(path, "near-peer", NotebookHistoryBudget(maxDocumentBytes = 1024 * 1024, warnFraction = 0.0001)).use { notebooks ->
            // The budget check runs off the constructing thread; listing waits for it.
            assertEquals(1, notebooks.listDocuments().size)
            val near = notebooks.faults.faults.value.single()
            assertEquals(CanvasStorageFault.Kind.NEAR_BUDGET, near.kind)
            assertTrue(!near.isError)
        }
    }
}
