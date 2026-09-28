package com.letta.mobile.data.canvas

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
