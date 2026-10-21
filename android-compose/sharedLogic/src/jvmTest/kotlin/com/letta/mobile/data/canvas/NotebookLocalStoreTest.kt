package com.letta.mobile.data.canvas

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.automerge.AmValue
import org.automerge.ChangeHash
import org.automerge.ObjectId
import org.automerge.repo.Repo
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey

class NotebookLocalStoreTest {
    private fun projectedNote(title: String, body: String) =
        "---\nschema: notebook/1\ntitle: \"$title\"\n---\n$body"

    @Test
    fun independentDocumentsReloadWithLocalEditsAndItems() {
        val directory = Files.createTempDirectory("notebook-store-test-")
        val firstId: org.automerge.repo.DocumentId
        val secondId: org.automerge.repo.DocumentId
        NotebookLocalStore(directory, "test-peer").use { store ->
            firstId = store.create("First")
            secondId = store.create("Second")
            store.insertMarkdown(firstId, 0, "---\ntitle: First\n---\n\nHello")
            store.putItem(firstId, NotebookItem("chat-1", "chat", conversationId = "conversation-a"))
            store.putItem(firstId, NotebookItem("chat-2", "chat", conversationId = "conversation-b"))
            store.setBoard(firstId, "{\"schema\":\"notebook-board/1\",\"elements\":[]}")
        }
        NotebookLocalStore(directory, "test-peer").use { store ->
            val first = assertNotNull(store.read(firstId))
            assertEquals(firstId.getBytes().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }, first.id.value)
            assertEquals("First", first.title)
            assertEquals("{\"schema\":\"notebook-board/1\",\"elements\":[]}", first.sceneJson)
            assertEquals("---\ntitle: First\n---\n\nHello", first.markdown)
            assertEquals(setOf("chat-1", "chat-2"), first.items.map { it.id }.toSet())
            assertEquals(setOf("conversation-a", "conversation-b"), first.items.mapNotNull { it.conversationId }.toSet())
            assertEquals("", assertNotNull(store.read(secondId)).markdown)
        }
    }

    @Test
    fun listingSurvivesRestartAndDoesNotProjectUnprojectedDocuments() {
        val directory = Files.createTempDirectory("notebook-list-test-")
        val ids = NotebookLocalStore(directory, "list-peer").use { store ->
            listOf(store.create("First"), store.create("Second")).also {
                assertEquals(it, store.listDocuments())
            }
        }
        NotebookLocalStore(directory, "list-peer").use { store ->
            assertEquals(ids, store.listDocuments())
            assertEquals("First", store.read(store.listDocuments().first())?.title)
            assertEquals(emptyMap(), store.pollProjections())
            assertTrue(!Files.exists(directory.resolve("projection")))
        }
    }

    @Test
    fun remoteDocumentRegistrationIsIdempotentAndDurable() {
        val directory = Files.createTempDirectory("notebook-remote-index-")
        val id = NotebookLocalStore(directory, "remote-peer").use { store ->
            store.create("Remote").also { store.registerDocument(it); store.registerDocument(it) }
        }
        NotebookLocalStore(directory, "remote-peer").use { store ->
            assertEquals(listOf(id), store.listDocuments())
        }
    }

    @Test
    fun pollingImportsExternalEditsAndReportsConflictAfterRestart() {
        val directory = Files.createTempDirectory("notebook-poll-test-")
        val id = NotebookLocalStore(directory, "poll-peer").use { store ->
            store.create("Note").also { store.project(it) }
        }
        val note = Files.list(directory.resolve("projection")).use { stream ->
            stream.findFirst().orElseThrow().resolve("note.md")
        }
        NotebookLocalStore(directory, "poll-peer").use { store ->
            Files.writeString(note, projectedNote("Note", "\nExternal"))
            assertEquals(NotebookProjectionResult.IMPORTED, store.pollProjections()[id])
            assertEquals("\nExternal", store.read(id)?.markdown)
            store.insertMarkdown(id, 0, "Local ")
            Files.writeString(note, projectedNote("Note", "\nOther"))
            assertEquals(NotebookProjectionResult.CONFLICT, store.pollProjections()[id])
            assertEquals(projectedNote("Note", "\nOther"), Files.readString(note))
            assertTrue(store.read(id)!!.markdown.startsWith("Local "))
        }
    }

    @Test
    fun pollingExportsNewDocumentsBeforeImportingExternalEdits() {
        val directory = Files.createTempDirectory("notebook-auto-projection-")
        NotebookLocalStore(directory, "auto-peer").use { store ->
            val id = store.create("Auto")
            store.insertMarkdown(id, 0, "---\ntitle: Auto\n---\n\nInitial")
            val exported = CountDownLatch(1)
            val imported = CountDownLatch(1)
            store.startPolling(25) { _, result ->
                if (result == NotebookProjectionResult.EXPORTED) exported.countDown()
                if (result == NotebookProjectionResult.IMPORTED) imported.countDown()
            }
            assertTrue(exported.await(5, TimeUnit.SECONDS))
            val folder = Files.list(directory.resolve("projection")).use { it.findFirst().orElseThrow() }
            Files.writeString(folder.resolve("note.md"), projectedNote("Auto", "\nExternal"))
            assertTrue(imported.await(5, TimeUnit.SECONDS))
            assertTrue(assertNotNull(store.read(id)).markdown.endsWith("External"))
        }
    }

    @Test
    fun malformedDocumentDoesNotStopPollingOtherDocuments() {
        val directory = Files.createTempDirectory("notebook-malformed-")
        NotebookLocalStore(directory, "malformed-peer").use { store ->
            val bad = store.create("Bad")
            val good = store.create("Good")
            store.project(bad)
            store.project(good)
            val folders = Files.list(directory.resolve("projection")).use { it.toList() }
            val badFolder = folders.first { Files.readString(it.resolve("note.md")).contains("Bad") }
            val goodFolder = folders.first { it != badFolder }
            Files.writeString(badFolder.resolve("note.md"), "---\nbroken")
            Files.writeString(goodFolder.resolve("note.md"), projectedNote("Good", "Updated"))
            val results = store.pollProjections()
            assertEquals(NotebookProjectionResult.ERROR, results[bad])
            assertEquals(NotebookProjectionResult.IMPORTED, results[good])
            val seen = CountDownLatch(2)
            store.startPolling(20) { id, result ->
                if (id == bad && result == NotebookProjectionResult.ERROR) seen.countDown()
            }
            assertTrue(seen.await(5, TimeUnit.SECONDS))
            assertEquals("Updated", store.read(good)?.markdown)
        }
    }

    @Test
    fun externalTitleAndVersionedBoardImport() {
        val directory = Files.createTempDirectory("notebook-board-import-")
        NotebookLocalStore(directory, "board-import-peer").use { store ->
            val id = store.create("Original")
            assertEquals(NotebookProjectionResult.EXPORTED, store.project(id))
            val folder = Files.list(directory.resolve("projection")).use { it.findFirst().orElseThrow() }
            Files.writeString(folder.resolve("note.md"), projectedNote("Renamed", "Body"))
            Files.writeString(folder.resolve("board.yaml"), "schema: notebook-board/1\nboardVersion: 1\nboard: |-\n  {\"schema\":\"notebook-board/1\",\"elements\":[1]}\n")
            assertEquals(NotebookProjectionResult.IMPORTED, store.reconcile(id))
            assertEquals("Renamed", store.read(id)?.title)
            assertEquals("Body", store.read(id)?.markdown)
            assertTrue(store.read(id)!!.sceneJson.contains("[1]"))
            Files.writeString(folder.resolve("board.yaml"), "schema: notebook-board/2\nboardVersion: 2\nboard: |-\n  {}\n")
            assertEquals(NotebookProjectionResult.ERROR, store.reconcile(id))
            assertTrue(store.read(id)!!.sceneJson.contains("[1]"))
        }
    }

    @Test
    fun deletedProjectionConflictsWithoutRestoringIt() {
        val directory = Files.createTempDirectory("notebook-deletion-")
        NotebookLocalStore(directory, "deletion-peer").use { store ->
            val id = store.create("Note")
            store.project(id)
            val folder = Files.list(directory.resolve("projection")).use { it.findFirst().orElseThrow() }
            val note = folder.resolve("note.md")
            Files.delete(note)
            assertEquals(NotebookProjectionResult.CONFLICT, store.reconcile(id))
            assertTrue(!Files.exists(note))
        }
    }

    @Test
    fun pollingStopsOnClose() {
        val directory = Files.createTempDirectory("notebook-poll-close-")
        val store = NotebookLocalStore(directory, "close-peer")
        val id = store.create("Note")
        store.project(id)
        val observed = CountDownLatch(1)
        store.startPolling(10) { _, _ -> observed.countDown() }
        assertTrue(observed.await(5, TimeUnit.SECONDS))
        store.close()
        assertFailsWith<IllegalStateException> { store.startPolling(10) }
        store.close()
    }

    @Test
    fun granularBoardRetainsMetadataAcrossRestart() {
        val directory = Files.createTempDirectory("notebook-granular-")
        val id = NotebookLocalStore(directory, "granular-peer").use { store ->
            val id = store.create("Board")
            store.setBoard(id, """{"schema":"notebook-board/1","elements":[{"id":"a","extra":42},{"id":"b"}],"documents":{"note":"body"}}""")
            store.putBoardElement(id, Json.parseToJsonElement("""{"id":"a","color":"red"}""").jsonObject)
            val board = Json.parseToJsonElement(store.read(id)!!.sceneJson).jsonObject
            assertEquals(2, (board["elements"] as kotlinx.serialization.json.JsonArray).size)
            assertTrue(!board["elements"].toString().contains("extra"))
            assertTrue(board["elements"].toString().contains("red"))
            store.removeBoardElement(id, "b")
            id
        }
        NotebookLocalStore(directory, "granular-peer").use { store ->
            val board = Json.parseToJsonElement(store.read(id)!!.sceneJson).jsonObject
            assertEquals(1, (board["elements"] as kotlinx.serialization.json.JsonArray).size)
            assertTrue(board["documents"].toString().contains("body"))
        }
    }

    @Test
    fun boardSchemaRejectsUnknownVersionWithoutChangingDocument() {
        val directory = Files.createTempDirectory("notebook-board-schema-")
        NotebookLocalStore(directory, "schema-peer").use { store ->
            val id = store.create("Board")
            val original = assertNotNull(store.read(id)).sceneJson
            assertFailsWith<IllegalArgumentException> {
                store.setBoard(id, "{\"schema\":\"notebook-board/2\",\"elements\":[]}")
            }
            assertEquals(original, assertNotNull(store.read(id)).sceneJson)
        }
    }

    @Test
    fun externalMarkdownEditImportsAndConcurrentEditsReportConflict() {
        val directory = Files.createTempDirectory("notebook-projection-test-")
        NotebookLocalStore(directory, "projection-peer").use { store ->
            val id = store.create("Note")
            store.insertMarkdown(id, 0, "---\ntitle: Note\n---\n\nInitial")
            assertEquals(NotebookProjectionResult.EXPORTED, store.project(id))
            val projection = directory.resolve("projection")
            val folder = Files.list(projection).use { it.findFirst().orElseThrow() }
            val note = folder.resolve("note.md")
            assertEquals(projectedNote("Note", "---\ntitle: Note\n---\n\nInitial"), Files.readString(note))
            Files.writeString(note, projectedNote("Note", "\nExternal"))
            assertEquals(NotebookProjectionResult.IMPORTED, store.reconcile(id))
            assertTrue(assertNotNull(store.read(id)).markdown.endsWith("External"))
            store.insertMarkdown(id, 0, "Local ")
            Files.writeString(note, projectedNote("Note", "\nOther external"))
            assertEquals(NotebookProjectionResult.CONFLICT, store.reconcile(id))
            assertTrue(assertNotNull(store.read(id)).markdown.startsWith("Local "))
        }
    }

    /** Holds the repository's first snapshot write open, written but unacknowledged, until [release]. */
    private class FirstSnapshotGate {
        private val held = AtomicBoolean()
        val written = CountDownLatch(1)
        val release = CompletableFuture<Void>()

        fun wrap(storage: Storage): Storage = object : Storage by storage {
            override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> {
                val put = storage.put(key, value)
                if (key.parts.getOrNull(1) != "snapshot" || !held.compareAndSet(false, true)) return put
                return put.thenCompose { written.countDown(); release }
            }
        }
    }

    /**
     * The repository deletes the chunks a compaction supersedes on its next save, and a snapshot
     * acknowledged while it is stopping gets none: the store's size then depended on when it
     * closed (letta-mobile-u8zni). Closing still leaves one snapshot, with every change in it.
     */
    @Test
    fun aCompactionAcknowledgedWhileClosingStillLeavesOneSnapshotWithAllHistory() {
        val directory = Files.createTempDirectory("notebook-compaction-close-")
        val gate = FirstSnapshotGate()
        val store = NotebookLocalStore(directory, "compaction-peer", storage = gate::wrap)
        val id = store.create("Compaction")
        var halfway = emptyArray<ChangeHash>()
        repeat(EDITS) { i ->
            store.insertMarkdown(id, 0, "$i,")
            if (i == EDITS / 2) halfway = assertNotNull(store.open(id)).withDocument { it.heads }.get(5, TimeUnit.SECONDS)
        }
        assertTrue(gate.written.await(5, TimeUnit.SECONDS), "the repository compacted")
        // Stop, and let every document actor take the stop before the snapshot is acknowledged.
        store.repo.stop()
        val runtime = Repo::class.java.getDeclaredField("runtime").apply { isAccessible = true }.get(store.repo)
        val hub = runtime.javaClass.getDeclaredField("hubExecutor").apply { isAccessible = true }.get(runtime) as ExecutorService
        hub.submit {}.get(5, TimeUnit.SECONDS)
        gate.release.complete(null)
        store.close()

        val chunks = Files.walk(directory).use { files ->
            files.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.parent.fileName.toString() in setOf("snapshot", "incremental") }
                .toList()
        }
        assertEquals(1, chunks.size, "chunks left on disk after close")
        val expected = (EDITS - 1 downTo 0).joinToString("") { "$it," }
        NotebookLocalStore(directory, "compaction-peer").use { reopened ->
            assertEquals(expected, assertNotNull(reopened.read(id)).markdown)
            val earlier = assertNotNull(reopened.open(id)).withDocument { document ->
                val fork = document.fork(halfway)
                try {
                    fork.text((fork.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id).orElseThrow()
                } finally {
                    fork.free()
                }
            }.get(5, TimeUnit.SECONDS)
            assertEquals((EDITS / 2 downTo 0).joinToString("") { "$it," }, earlier)
        }
    }

    private companion object {
        /** Enough edits that the repository compacts (past ten chunks) and keeps writing after. */
        const val EDITS = 40
    }
}
