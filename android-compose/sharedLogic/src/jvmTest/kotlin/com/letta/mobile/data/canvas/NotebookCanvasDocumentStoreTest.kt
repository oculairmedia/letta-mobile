package com.letta.mobile.data.canvas

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotebookCanvasDocumentStoreTest {
    @Test
    fun revisionConflictPreservesAclAndIndependentNotebookData() = runBlocking {
        val path = Files.createTempDirectory("canvas-notebook-cas-")
        NotebookLocalStore(path, "canvas-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val original = CanvasDocument(CanvasId("first"), "agent", title = "First", revision = 1,
                sceneJson = """{"bgColor":"#ffffffff","elements":[]}""",
                acl = CanvasAcl("owner"))
            store.upsert(original)
            val id = notebooks.listDocuments().single()
            notebooks.putItem(id, NotebookItem("chat", "chat", conversationId = "other"))
            val updated = original.copy(revision = 2, title = "Changed", acl = CanvasAcl("new-owner"))
            assertTrue(store.upsertIfRevision(updated, 1))
            assertFalse(store.upsertIfRevision(original.copy(revision = 3), 1))
            assertEquals(updated.acl, store.get(original.id)?.acl)
            assertEquals(1, notebooks.read(id)?.items?.size)
        }
    }

    @Test
    fun creationRaceAndRestartKeepOneConversationAndCanvasIdentity() = runBlocking {
        val path = Files.createTempDirectory("canvas-notebook-race-")
        val first = CanvasDocument(CanvasId.forConversation("chat"), "agent", "chat", "One", sceneJson = "")
        val second = first.copy(id = CanvasId("other"), title = "Two")
        val winner = NotebookLocalStore(path, "canvas-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val results = coroutineScope {
                listOf(first, second).map { candidate ->
                    async(Dispatchers.IO) { store.createForConversationIfAbsent(candidate) }
                }.map { it.await() }
            }
            assertEquals(results[0], results[1])
            assertEquals(1, store.listAll().size)
            results.first()
        }
        NotebookLocalStore(path, "canvas-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            assertEquals(winner.id, store.getForConversation("chat")?.id)
            assertEquals(winner, store.createForConversationIfAbsent(second))
            assertEquals(1, notebooks.listDocuments().size)
        }
    }

    @Test
    fun listsAndCanvasWritesPreserveDistinctNotebookElements() = runBlocking {
        val path = Files.createTempDirectory("canvas-notebook-list-")
        NotebookLocalStore(path, "canvas-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val a = CanvasDocument(CanvasId("a"), "agent", "chat", "A", 1,
                """{"bgColor":"#ffffffff","elements":[{"id":"a","text":"old"}]}""", 1)
            val b = CanvasDocument(CanvasId("b"), "other", null, "B", 0,
                """{"bgColor":"#ffffffff","elements":[]}""", 2)
            store.upsert(a)
            store.upsert(b)
            val notebookId = notebooks.listDocuments().first()
            notebooks.putBoardElement(notebookId, Json.parseToJsonElement("""{"id":"independent","text":"kept"}""").jsonObject)
            store.upsertIfRevision(a.copy(revision = 2, sceneJson =
                """{"bgColor":"#ffffffff","elements":[{"id":"a","text":"new"}]}"""), 1)
            val scene = Json.parseToJsonElement(assertNotNull(store.get(a.id)).sceneJson).jsonObject
            assertEquals(setOf("a", "independent"), scene.getValue("elements").jsonArray.map {
                it.jsonObject.getValue("id").toString().trim('"')
            }.toSet())
            assertEquals(listOf(b.id, a.id), store.listAll().map { it.id })
            assertEquals(listOf(a.id), store.listForAgent("agent").map { it.id })
            assertEquals(a.id, store.getForConversation("chat")?.id)
        }
    }
}
