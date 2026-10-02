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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Storage failures are non-fatal but loud; over-budget histories are archived and moved, not lost. */
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

    /**
     * A canvas whose history is bloated the way the old storage did it: one large string rewritten
     * many times. Element `b` was deleted, so it has a tombstone and a restore-list entry.
     */
    private fun bloatedCanvas(path: Path, canvasId: CanvasId): DocumentId = runBlocking {
        NotebookLocalStore(path, "budget-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val withB = bloatedScene.removeSuffix("]}") + """,{"id":"b","type":"Rect"}]}"""
            store.upsert(CanvasDocument(canvasId, conversationId = "conversation-${canvasId.value}", title = "Bloated", sceneJson = withB))
            store.upsert(CanvasDocument(canvasId, conversationId = "conversation-${canvasId.value}", title = "Bloated", revision = 1, sceneJson = bloatedScene))
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
            // The store turns read-only before it records why; wait for both.
            waitFor { notebooks.isReadOnly && notebooks.faults.faults.value.any { it.kind == CanvasStorageFault.Kind.READ_ONLY } }
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

    private fun DocumentId.root(notebooks: NotebookLocalStore, key: String): AmValue? = notebooks.open(this)!!.withDocument { document ->
        document.get(ObjectId.ROOT, key).orElse(null)
    }.get(5, TimeUnit.SECONDS)

    private fun archivesOf(path: Path, key: String): List<String> =
        Files.list(NotebookHistoryArchive(path).archiveRoot.resolve(key)).use { s -> s.iterator().asSequence().map { it.name }.toList() }

    @Test
    fun anOverBudgetHistoryInASyncedStoreIsArchivedAndTheBoardMovesToANewDocument() = runBlocking {
        val path = Files.createTempDirectory("notebook-budget-")
        val canvasId = CanvasId("bloated")
        val id = bloatedCanvas(path, canvasId)
        val key = id.key()
        val archive = NotebookHistoryArchive(path)
        val before = archive.documentBytes(key)
        assertTrue(before > smallBudget.maxDocumentBytes, "history only reached $before bytes")

        NotebookLocalStore(path, "budget-peer", smallBudget).use { notebooks ->
            // A store that syncs with peers: the move never touches the id they hold.
            notebooks.repoForSync()
            val moved = notebooks.listDocuments().single()
            assertNotEquals(id, moved)
            val compacted = notebooks.faults.faults.value.single { it.kind == CanvasStorageFault.Kind.COMPACTED }
            assertEquals(moved.key(), compacted.documentId)
            assertEquals(canvasId, compacted.canvasId)
            assertFalse(compacted.isError)
            assertTrue(notebooks.faults.faults.value.none { it.isError }, notebooks.faults.faults.value.toString())
            assertTrue(archive.documentBytes(moved.key()) < before / 4, "moved at ${archive.documentBytes(moved.key())} of $before")
            // The old id is retired: not opened, its storage deleted, recorded durably, never registered again.
            assertNull(notebooks.open(id))
            assertFalse(Files.exists(archive.documentDirectory(key)))
            assertTrue(key in NotebookDocumentIndex(path).retired())
            assertFalse(notebooks.registerDocument(id))
            assertEquals(listOf(moved), notebooks.listDocuments())
            // The same canvas, found by id and by conversation, with the same content.
            val store = NotebookCanvasDocumentStore(notebooks)
            val doc = store.get(canvasId)!!
            assertEquals(doc, store.getForConversation("conversation-bloated"))
            assertEquals(Json.parseToJsonElement(bloatedScene), Json.parseToJsonElement(doc.sceneJson))
            assertEquals("Kept text", notebooks.read(moved)!!.markdown)
            // The deleted element is still restorable; its tombstone did not come along.
            assertEquals(listOf("b"), store.deletedElements(canvasId).map { it.elementId })
            val tombstones = (moved.root(notebooks, "boardTombstones") as AmValue.Map).id
            assertTrue(notebooks.open(moved)!!.withDocument { it.keys(tombstones).orElseThrow().isEmpty() }.get(5, TimeUnit.SECONDS))
            // It names the document it replaced and its archive, and the archive holds the full history.
            assertEquals(key, (moved.root(notebooks, NotebookHistoryArchive.PREVIOUS_DOCUMENT) as AmValue.Str).value)
            val entry = notebooks.open(moved)!!.withDocument { document ->
                val list = (document.get(ObjectId.ROOT, NotebookHistoryArchive.HISTORY_ARCHIVE).orElseThrow() as AmValue.List).id
                (document.listItems(list).orElseThrow().single() as AmValue.Str).value
            }.get(5, TimeUnit.SECONDS).let { Json.parseToJsonElement(it).jsonObject }
            assertEquals(key, entry.getValue("document").jsonPrimitive.content)
            val archived = archive.archiveRoot.resolve(key).resolve(entry.getValue("file").jsonPrimitive.content)
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
        NotebookLocalStore(path, "budget-peer", smallBudget).use { notebooks ->
            assertTrue(NotebookCanvasDocumentStore(notebooks).get(canvasId)!!.sceneJson.contains("5,6"))
            assertEquals(1, notebooks.listDocuments().size)
            assertTrue(notebooks.faults.faults.value.none { it.isError || it.kind == CanvasStorageFault.Kind.COMPACTED })
            assertEquals(1, archivesOf(path, key).size, archivesOf(path, key).toString())
        }
    }

    @Test
    fun anOverBudgetHistoryThatCannotBeMovedStaysAsItIsAndSaysSoLoudly() {
        val path = Files.createTempDirectory("notebook-budget-unmovable-")
        val id = NotebookLocalStore(path, "budget-peer").use { it.create("Unmovable") }
        val key = id.key()
        val directory = NotebookHistoryArchive(path).documentDirectory(key)
        // A chunk that is not Automerge: the history cannot be loaded to copy its state.
        Files.write(Files.createDirectories(directory.resolve("snapshot")).resolve("ee".repeat(32)), ByteArray(12 * 1024) { 7 })
        val size = bytesUnder(directory)
        NotebookLocalStore(path, "budget-peer", NotebookHistoryBudget(maxDocumentBytes = 8 * 1024)).use { notebooks ->
            assertEquals(listOf(id), notebooks.listDocuments())
            val over = notebooks.faults.faults.value.single { it.kind == CanvasStorageFault.Kind.OVER_BUDGET }
            assertTrue(over.isError)
            assertEquals(key, over.documentId)
            assertTrue(over.message.contains("could not be archived and moved"), over.message)
            assertTrue(notebooks.faults.faults.value.none { it.kind == CanvasStorageFault.Kind.COMPACTED })
        }
        assertEquals(size, bytesUnder(directory))
        assertTrue(NotebookDocumentIndex(path).retired().isEmpty())
        assertTrue(archivesOf(path, key).none { !it.endsWith(".automerge.gz") }, archivesOf(path, key).toString())
    }

    @Test
    fun twoStoresOpeningAtOnceMoveADocumentOnlyOnce() = runBlocking {
        val path = Files.createTempDirectory("notebook-budget-race-")
        val id = bloatedCanvas(path, CanvasId("raced"))
        val first = NotebookLocalStore(path, "budget-peer", smallBudget)
        val second = NotebookLocalStore(path, "budget-peer", smallBudget)
        val moved = try {
            val moved = first.listDocuments().single()
            // The second re-reads the index under the lock and finds the document already moved.
            assertEquals(listOf(moved), second.listDocuments())
            assertNotEquals(id, moved)
            assertNull(second.open(id))
            val compactions = (first.faults.faults.value + second.faults.faults.value).count { it.kind == CanvasStorageFault.Kind.COMPACTED }
            assertEquals(1, compactions)
            val archives = archivesOf(path, id.key())
            assertEquals(1, archives.count { it.endsWith(".gz") }, archives.toString())
            assertEquals(archives.size, archives.count { it.endsWith(".gz") }, archives.toString())
            moved
        } finally {
            first.close()
            second.close()
        }
        NotebookLocalStore(path, "budget-peer").use { notebooks ->
            assertEquals(listOf(moved), notebooks.listDocuments())
            assertEquals("Kept text", notebooks.read(moved)!!.markdown)
        }
    }

    @Test
    fun aDocumentTooLargeToMoveIsSetAsideNotOpened() {
        val path = Files.createTempDirectory("notebook-quarantine-")
        val id = NotebookLocalStore(path, "quarantine-peer").use { it.create("Broken") }
        val key = id.key()
        val directory = NotebookHistoryArchive(path).documentDirectory(key)
        // A chunk that is not Automerge and far over the budget: it cannot be archived and moved.
        Files.write(Files.createDirectories(directory.resolve("snapshot")).resolve("ff".repeat(32)), ByteArray(64 * 1024) { 7 })
        val size = bytesUnder(directory)
        val budget = NotebookHistoryBudget(maxDocumentBytes = 8 * 1024)
        NotebookLocalStore(path, "quarantine-peer", budget).use { notebooks ->
            notebooks.listDocuments()
            val fault = notebooks.faults.faults.value.single { it.kind == CanvasStorageFault.Kind.QUARANTINED }
            assertEquals(key, fault.documentId)
            assertEquals(size, fault.documentBytes)
            assertTrue(notebooks.listDocuments().isEmpty())
            assertEquals(null, notebooks.open(id))
        }
        // The files are untouched for manual recovery, and the failed move left nothing behind.
        assertEquals(size, bytesUnder(directory))
        val leftovers = NotebookHistoryArchive(path).archiveRoot.resolve(key).let { dir ->
            if (!Files.isDirectory(dir)) emptyList() else Files.list(dir).use { s -> s.iterator().asSequence().map { it.name }.toList() }
        }
        assertTrue(leftovers.none { it.endsWith(".successor") || it.endsWith(".building") }, leftovers.toString())
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
