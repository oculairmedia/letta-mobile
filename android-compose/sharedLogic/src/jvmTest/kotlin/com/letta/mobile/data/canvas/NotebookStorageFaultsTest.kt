package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinPool
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
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun documentStorageLivesWhereTheArchiveLooksForIt() {
        val path = Files.createTempDirectory("notebook-layout-")
        val id = NotebookLocalStore(path, "layout-peer").use { it.create("Layout") }
        val key = id.bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val directory = NotebookHistoryArchive(path).documentDirectory(key)
        assertTrue(Files.isDirectory(directory), "expected $directory")
        assertTrue(NotebookHistoryArchive(path).documentBytes(key) > 0)
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
    fun anErrorOnARepositoryThreadDoesNotKillTheProcessAndIsRecorded() {
        val path = Files.createTempDirectory("notebook-guard-")
        NotebookLocalStore(path, "guard-peer").use { notebooks ->
            notebooks.create("Guarded")
            val oom = OutOfMemoryError("Failed to allocate a 62600352 byte allocation (simulated)").apply {
                stackTrace = arrayOf(
                    StackTraceElement("java.lang.Object", "clone", null, -2),
                    StackTraceElement("org.automerge.repo.StorageTask\$Put", "<init>", "StorageTask.java", 93),
                    StackTraceElement("org.automerge.repo.RepoRuntime", "routeIoResultToActor", "RepoRuntime.java", 472),
                )
            }
            // Exactly where the phone died: thrown out of a task on the repository's work-stealing pool.
            val pool = ForkJoinPool(1)
            pool.execute { throw oom }
            waitFor { notebooks.faults.faults.value.any { it.kind == CanvasStorageFault.Kind.SAVE_FAILED } }
            pool.shutdown()
            val fault = notebooks.faults.faults.value.single { it.kind == CanvasStorageFault.Kind.SAVE_FAILED }
            assertNotNull(fault.documentId)
            assertTrue(fault.message.contains("ForkJoinPool"), fault.message)
            // Errors that are not the repository's still reach the previous handler.
            assertTrue(!AutomergeFaultGuard.handles(Thread("main"), oom))
            assertTrue(!AutomergeFaultGuard.handles(Thread("worker"), OutOfMemoryError("app code")))
        }
    }

    @Test
    fun anOverBudgetHistoryIsArchivedAndTheDocumentContinuesFromItsCurrentState() = runBlocking {
        val path = Files.createTempDirectory("notebook-budget-")
        val canvasId = CanvasId("bloated")
        val budget = NotebookHistoryBudget(maxDocumentBytes = 256 * 1024)
        val scene = """{"bgColor":"#ffffffff","elements":[{"id":"a","type":"Path","points":["1,2"]}]}"""
        val id = NotebookLocalStore(path, "budget-peer", NotebookHistoryBudget(compactOversized = false)).use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            store.upsert(CanvasDocument(canvasId, title = "Bloated", sceneJson = scene))
            val id = notebooks.listDocuments().single()
            notebooks.insertMarkdown(id, 0, "Kept text")
            // Bloat the history the way the old storage did: a large string rewritten many times.
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
        val key = id.bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
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
            assertEquals(Json.parseToJsonElement(scene), Json.parseToJsonElement(doc.sceneJson))
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
            assertTrue(store.upsertIfRevision(doc.copy(revision = doc.revision + 1, sceneJson = scene.replace("1,2", "5,6")), doc.revision))
        }
        NotebookLocalStore(path, "budget-peer", budget).use { notebooks ->
            assertTrue(NotebookCanvasDocumentStore(notebooks).get(canvasId)!!.sceneJson.contains("5,6"))
            assertTrue(notebooks.faults.faults.value.none { it.isError })
            assertEquals(1, Files.list(archive.archiveRoot.resolve(key)).use { s -> s.iterator().asSequence().count { it.name.endsWith(".gz") } })
        }
    }

    @Test
    fun aDocumentTooLargeToRestartIsSetAsideNotOpened() {
        val path = Files.createTempDirectory("notebook-quarantine-")
        val id = NotebookLocalStore(path, "quarantine-peer").use { it.create("Broken") }
        val key = id.bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val directory = NotebookHistoryArchive(path).documentDirectory(key)
        // A chunk that is not Automerge and far over the budget: it cannot be archived and restarted.
        Files.write(Files.createDirectories(directory.resolve("snapshot")).resolve("ff".repeat(32)), ByteArray(64 * 1024) { 7 })
        val size = bytesUnder(directory)
        NotebookLocalStore(path, "quarantine-peer", NotebookHistoryBudget(maxDocumentBytes = 8 * 1024)).use { notebooks ->
            notebooks.listDocuments()
            val fault = notebooks.faults.faults.value.single { it.kind == CanvasStorageFault.Kind.QUARANTINED }
            assertEquals(key, fault.documentId)
            assertEquals(size, fault.documentBytes)
            assertTrue(notebooks.listDocuments().isEmpty())
            assertEquals(null, notebooks.open(id))
        }
        // The files are untouched for manual recovery.
        assertEquals(size, bytesUnder(directory))
    }

    @Test
    fun approachingTheBudgetIsAWarningNotAnError() {
        val path = Files.createTempDirectory("notebook-near-budget-")
        NotebookLocalStore(path, "near-peer").use { it.create("Near") }
        NotebookLocalStore(path, "near-peer", NotebookHistoryBudget(maxDocumentBytes = 1024 * 1024, warnFraction = 0.0001)).use { notebooks ->
            val near = notebooks.faults.faults.value.single()
            assertEquals(CanvasStorageFault.Kind.NEAR_BUDGET, near.kind)
            assertTrue(!near.isError)
            assertEquals(1, notebooks.listDocuments().size)
        }
    }
}
