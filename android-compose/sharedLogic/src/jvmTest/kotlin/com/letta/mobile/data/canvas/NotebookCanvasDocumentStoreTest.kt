package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NotebookCanvasDocumentStoreTest {
    @Test
    fun readOnlyNotebookCannotAcknowledgeADraftAfterItsDiagnosticIsEvicted() = runBlocking {
        val path = Files.createTempDirectory("canvas-read-only-authority-")
        NotebookLocalStore(path, "read-only-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val session = CanvasSession.create(store)
            val id = notebooks.listDocuments().single()
            notebooks.open(id)!!.withDocument { document ->
                document.startTransaction().use { tx ->
                    tx.set(org.automerge.ObjectId.ROOT, "boardVersion", NotebookBoardStorage.LAYOUT_VERSION + 1)
                    tx.commit()
                }
            }.get()
            store.get(session.canvasId)
            assertTrue(store.storageFaults.value.any { it.kind == CanvasStorageFault.Kind.READ_ONLY })
            repeat(64) { index ->
                notebooks.faults.record(CanvasStorageFault(CanvasStorageFault.Kind.NEAR_BUDGET, "other-$index",
                    CanvasId("other"), null, "unrelated budget notice", 1))
            }
            assertTrue(store.storageFaults.value.none { it.kind == CanvasStorageFault.Kind.READ_ONLY })
            assertFailsWith<IllegalStateException> { session.applyLocalScene("""{"elements":[{"id":"draft"}]}""") }
            assertFalse(session.sceneJsonOrEmpty().contains("draft"))
            assertFalse(store.get(session.canvasId)!!.sceneJson.contains("draft"))
            assertTrue(session.opLog.unsettled(session.canvasId).isNotEmpty())
        }
    }

    @Test
    fun drawingAcknowledgmentWaitsForStorageCompletion() = runBlocking {
        val path = Files.createTempDirectory("canvas-durable-gate-")
        val hold = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = java.util.concurrent.CompletableFuture<Unit>()
        val release = java.util.concurrent.CompletableFuture<Void>()
        val wrap = { delegate: org.automerge.repo.Storage ->
            object : org.automerge.repo.Storage by delegate {
                override fun put(key: org.automerge.repo.StorageKey, value: ByteArray): java.util.concurrent.CompletableFuture<Void> =
                    if (hold.get()) {
                        entered.complete(Unit)
                        release.thenCompose { delegate.put(key, value) }
                    } else delegate.put(key, value)
            }
        }
        NotebookLocalStore(path, "gated-peer", storage = wrap).use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val session = CanvasSession.create(store)
            val baseline = session.sceneJsonOrEmpty()
            hold.set(true)
            val saving = async(Dispatchers.Default) { session.applyLocalScene("""{"elements":[{"id":"gated"}]}""") }
            try {
                entered.get(10, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(null, kotlinx.coroutines.withTimeoutOrNull(1.seconds) { saving.await() },
                    "an accepted CRDT transaction is not a durable save")
                assertEquals(baseline, session.sceneJsonOrEmpty())
            } finally {
                release.complete(null)
            }
            saving.await()
            NotebookLocalStore(path, "gated-restart").use { restarted ->
                assertTrue(NotebookCanvasDocumentStore(restarted).get(session.canvasId)!!.sceneJson.contains("gated"))
            }
        }
    }

    @Test
    fun failedDrawingSavesRetryToDiskBeforeAcknowledgment() = runBlocking {
        val path = Files.createTempDirectory("canvas-durable-retry-")
        val failing = java.util.concurrent.atomic.AtomicBoolean(false)
        val puts = java.util.concurrent.atomic.AtomicInteger()
        val wrap = { delegate: org.automerge.repo.Storage ->
            object : org.automerge.repo.Storage by delegate {
                override fun put(key: org.automerge.repo.StorageKey, value: ByteArray): java.util.concurrent.CompletableFuture<Void> {
                    puts.incrementAndGet()
                    if (failing.get()) return java.util.concurrent.CompletableFuture.failedFuture(java.io.IOException("same disk failure"))
                    return delegate.put(key, value)
                }
            }
        }
        NotebookLocalStore(path, "retry-peer", storage = wrap).use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val session = CanvasSession.create(store, CanvasCreateOptions())
            val baseline = session.sceneJsonOrEmpty()
            val draft = """{"bgColor":"#f7f3eaff","elements":[{"id":"draft"}]}"""
            failing.set(true)
            repeat(2) {
                val before = puts.get()
                assertFailsWith<IllegalStateException> { session.applyLocalScene(draft, baseJson = baseline) }
                assertTrue(puts.get() > before)
                assertTrue(session.opLog.unsettled(session.canvasId).isNotEmpty())
            }
            val firstFailureTime = store.storageFaults.value.first { it.kind == CanvasStorageFault.Kind.SAVE_FAILED }.atEpochMs
            failing.set(false)
            val before = puts.get()
            assertTrue(session.applyLocalScene(draft).isEmpty(), "retry persists even when recovery leaves no new drawing ops")
            assertTrue(puts.get() > before)
            assertTrue(session.opLog.unsettled(session.canvasId).isEmpty())
            assertEquals(firstFailureTime, store.storageFaults.value.first { it.kind == CanvasStorageFault.Kind.SAVE_FAILED }.atEpochMs)
            // Reopen before closing the original repository: shutdown must not rescue an early acknowledgment.
            NotebookLocalStore(path, "restart-peer").use { restarted ->
                val loaded = assertNotNull(NotebookCanvasDocumentStore(restarted).get(session.canvasId))
                assertTrue(loaded.sceneJson.contains("draft"))
                assertTrue(loaded.sceneJson.contains("#f7f3eaff"))
            }
        }
    }

    @Test
    fun deletedDrawingItemsSurviveRestartAndRestoreOnlyTheirOwnElement() = runBlocking {
        val path = Files.createTempDirectory("canvas-deleted-history-")
        val canvasId = CanvasId("deleted-history")
        NotebookLocalStore(path, "canvas-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val session = CanvasSession.create(store, CanvasCreateOptions(canvasId = canvasId))
            session.applyLocal(CanvasOp.AddElementOp("add-a", "local_user", 1, "a", """{"id":"a","text":"original"}"""))
            session.applyLocal(CanvasOp.AddElementOp("add-b", "local_user", 2, "b", """{"id":"b"}"""))
            session.applyLocal(CanvasOp.RemoveElementOp("remove-a", "local_user", 3, "a"))
            assertEquals(listOf("a"), session.deletedElements().map { it.elementId })
            val unrelated = assertNotNull(store.get(canvasId))
            store.upsert(unrelated.copy(revision = unrelated.revision + 1, sceneJson =
                unrelated.sceneJson.replace("\"id\":\"b\"", "\"id\":\"b\",\"text\":\"changed\"")))
            assertEquals(listOf("a"), session.deletedElements().map { it.elementId })
        }
        NotebookLocalStore(path, "canvas-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val session = assertNotNull(CanvasSession.open(store, canvasId))
            assertEquals(listOf("a"), session.deletedElements().map { it.elementId })
            val restored = assertNotNull(session.restoreDeletedElement(CanvasDeletedElement("a", "")))
            val elements = Json.parseToJsonElement(restored.sceneJson).jsonObject.getValue("elements").jsonArray
            assertEquals(setOf("a", "b"), elements.map { it.jsonObject.getValue("id").toString().trim('"') }.toSet())
            assertTrue(session.deletedElements().isEmpty())
            assertTrue(session.opLog.getOps(canvasId).last() is CanvasOp.AddElementOp)
        }
    }
    @Test
    fun staleSnapshotCannotReplaceAnotherPeersLiveElement() = runBlocking {
        val path = Files.createTempDirectory("canvas-stale-restore-")
        NotebookLocalStore(path, "peer-a").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val session = CanvasSession.create(store, CanvasCreateOptions(canvasId = CanvasId("stale")))
            session.applyLocal(CanvasOp.AddElementOp("add", "local_user", 1, "item", """{"id":"item","text":"old"}"""))
            session.applyLocal(CanvasOp.RemoveElementOp("remove", "local_user", 2, "item"))
            val id = notebooks.listDocuments().single()
            // A second writer has re-added this ID while this session still holds the deletion.
            notebooks.putBoardElement(id, Json.parseToJsonElement("""{"id":"item","text":"peer-b"}""").jsonObject)
            assertEquals(null, session.restoreDeletedElement(CanvasDeletedElement("item", "")))
            val live = Json.parseToJsonElement(assertNotNull(store.get(session.canvasId)).sceneJson)
                .jsonObject.getValue("elements").jsonArray.single().jsonObject
            assertEquals("peer-b", live.getValue("text").toString().trim('"'))
        }
    }

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
        val first = CanvasDocument(CanvasId.forConversation(ConversationId("chat")), "agent", "chat", "One", sceneJson = "")
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
            assertEquals(winner.id, store.getForConversation(ConversationId("chat"))?.id)
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
            assertEquals(listOf(a.id), store.listForAgent(AgentId("agent")).map { it.id })
            assertEquals(a.id, store.getForConversation(ConversationId("chat"))?.id)
        }
    }
}
