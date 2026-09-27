package com.letta.mobile.data.canvas

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

class NotebookLocalStoreTest {
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
            Files.writeString(note, "---\ntitle: Note\n---\n\nExternal")
            assertEquals(NotebookProjectionResult.IMPORTED, store.pollProjections()[id])
            assertEquals("---\ntitle: Note\n---\n\nExternal", store.read(id)?.markdown)
            store.insertMarkdown(id, 0, "Local ")
            Files.writeString(note, "---\ntitle: Note\n---\n\nOther")
            assertEquals(NotebookProjectionResult.CONFLICT, store.pollProjections()[id])
            assertEquals("---\ntitle: Note\n---\n\nOther", Files.readString(note))
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
            Files.writeString(folder.resolve("note.md"), "---\ntitle: Auto\n---\n\nExternal")
            assertTrue(imported.await(5, TimeUnit.SECONDS))
            assertTrue(assertNotNull(store.read(id)).markdown.endsWith("External"))
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
            assertEquals("---\ntitle: Note\n---\n\nInitial", Files.readString(note))
            Files.writeString(note, "---\ntitle: Note\n---\n\nExternal")
            assertEquals(NotebookProjectionResult.IMPORTED, store.reconcile(id))
            assertTrue(assertNotNull(store.read(id)).markdown.endsWith("External"))
            store.insertMarkdown(id, 0, "Local ")
            Files.writeString(note, "---\ntitle: Note\n---\n\nOther external")
            assertEquals(NotebookProjectionResult.CONFLICT, store.reconcile(id))
            assertTrue(assertNotNull(store.read(id)).markdown.startsWith("Local "))
        }
    }
}
