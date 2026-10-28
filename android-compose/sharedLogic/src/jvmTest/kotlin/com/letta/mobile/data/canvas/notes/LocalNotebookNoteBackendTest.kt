package com.letta.mobile.data.canvas.notes

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookItem
import com.letta.mobile.data.canvas.NotebookLocalStore
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.automerge.ObjectId
import org.automerge.repo.DocumentId
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalNotebookNoteBackendTest {
    @Test
    fun cancelledSaveAfterMutationRequiresStorageAcknowledgementBeforeClose() = runBlocking {
        val path = Files.createTempDirectory("note-editor-cancel-ack-")
        val blockWrites = AtomicBoolean(false)
        val enteredBarrier = CompletableFuture<Unit>()
        val pendingAck = CompletableFuture<Void>()
        val wrapper = { delegate: Storage ->
            object : Storage by delegate {
                override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> {
                    val explicitBarrier = Thread.currentThread().stackTrace.any {
                        it.className.endsWith("NotebookStorageHealth") && it.methodName == "persistChunk"
                    }
                    if (!blockWrites.get() || !explicitBarrier) return delegate.put(key, value)
                    return if (enteredBarrier.complete(Unit)) pendingAck
                    else CompletableFuture.failedFuture(IOException("acknowledgement unavailable"))
                }
            }
        }
        NotebookLocalStore(path, "cancel-ack-peer", storage = wrapper).use { store ->
            val storageId = store.create("Cancellation")
            assertTrue(store.persistCanvas(storageId))
            val id = assertNotNull(store.read(storageId)).id
            val session = NotebookNoteSession(id, LocalNotebookNoteBackend(store))
            session.refresh()
            session.edit("applied but not acknowledged")
            blockWrites.set(true)
            val saving = async { session.save() }
            try {
                withContext(Dispatchers.IO) { enteredBarrier.get(5, TimeUnit.SECONDS) }
                saving.cancelAndJoin()
                assertTrue(saving.isCancelled)
                assertFalse(pendingAck.isDone)
                assertEquals("applied but not acknowledged", store.read(storageId)?.markdown)
                assertTrue(session.state.value.dirty)
                assertEquals("applied but not acknowledged", session.state.value.indeterminateWrite?.markdown)

                assertFalse(withTimeout(5_000) { session.prepareClose() })
                assertTrue(session.state.value.dirty)
                assertNotNull(session.state.value.error)
                blockWrites.set(false)
                assertTrue(withTimeout(5_000) { session.retryPersistence() })
                assertFalse(session.state.value.dirty)
                assertTrue(session.prepareClose())
                NotebookLocalStore(path, "cancel-ack-reader").use { reader ->
                    assertEquals("applied but not acknowledged", reader.read(storageId)?.markdown)
                }
            } finally {
                saving.cancelAndJoin()
                pendingAck.completeExceptionally(IOException("test cleanup"))
            }
        }
    }

    @Test
    fun realStoreSessionSaveClosesAndReopensSameDocumentWithOpaqueFieldsIntact() = runBlocking {
        val path = Files.createTempDirectory("note-editor-reopen-")
        val id = NotebookLocalStore(path, "note-editor").use { store ->
            val storageId = store.create("Independent note")
            val original = assertNotNull(store.read(storageId))
            store.putItem(storageId, NotebookItem("opaque", "unknown-kind", payloadJson = "{\"future\":true}"))
            store.open(storageId)!!.withDocument { document ->
                document.startTransaction().use { tx ->
                    tx.set(ObjectId.ROOT, "futureField", "preserved")
                    tx.commit()
                }
            }.get()
            val session = NotebookNoteSession(original.id, LocalNotebookNoteBackend(store))
            session.refresh()
            session.edit("# Shared note\n😀 e\u0301")
            assertTrue(session.save())
            assertTrue(session.prepareClose())
            // Verify the barrier before shutdown can rescue a premature Saved result.
            NotebookLocalStore(path, "note-editor-durable-reader").use { reader ->
                assertEquals(session.state.value.draft, reader.read(storageId)?.markdown)
            }
            original.id
        }
        NotebookLocalStore(path, "note-editor-reopen").use { store ->
            val content = assertIs<NotebookNoteLoad.Found>(LocalNotebookNoteBackend(store).load(id)).snapshot
            assertEquals("# Shared note\n😀 e\u0301", content.markdown)
            assertEquals(id, content.id)
            val storageId = store.listDocuments().single()
            assertEquals("unknown-kind", store.read(storageId)?.items?.single()?.kind)
            assertEquals("{\"future\":true}", store.read(storageId)?.items?.single()?.payloadJson)
            val opaque = store.open(storageId)!!.withDocument { it.get(ObjectId.ROOT, "futureField").orElseThrow() }.get()
            assertEquals("preserved", (opaque as org.automerge.AmValue.Str).value)
        }
    }

    @Test
    fun staleRealStoreMutationPreservesPeerContentAndSessionDraft() = runBlocking {
        NotebookLocalStore(Files.createTempDirectory("note-editor-stale-"), "stale-peer").use { store ->
            val storageId = store.create("Stale")
            store.insertMarkdown(storageId, 0, "original")
            val id = assertNotNull(store.read(storageId)).id
            val session = NotebookNoteSession(id, LocalNotebookNoteBackend(store))
            session.refresh()
            session.edit("local draft")
            assertTrue(store.replaceMarkdown(storageId, "original", "peer edit"))
            assertFalse(session.save())
            assertEquals("peer edit", store.read(storageId)?.markdown)
            assertEquals("local draft", session.state.value.draft)
            assertTrue(session.state.value.conflict)
            assertFalse(session.prepareClose())
        }
    }

    @Test
    fun failedStorageReturnsPendingAndFlushRetryPersistsWithoutStaleReapplication() = runBlocking {
        val path = Files.createTempDirectory("note-editor-retry-")
        val failing = AtomicBoolean(false)
        val wrapper = { delegate: Storage ->
            object : Storage by delegate {
                override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> =
                    if (failing.get()) CompletableFuture.failedFuture(IOException("injected note disk failure"))
                    else delegate.put(key, value)
            }
        }
        NotebookLocalStore(path, "retry-peer", storage = wrapper).use { store ->
            val storageId = store.create("Retry")
            assertTrue(store.persistCanvas(storageId))
            val id = assertNotNull(store.read(storageId)).id
            val backend = LocalNotebookNoteBackend(store)
            val session = NotebookNoteSession(id, backend)
            session.refresh()
            session.edit("accepted")
            failing.set(true)
            assertFalse(session.save())
            assertEquals("accepted", store.read(storageId)?.markdown)
            assertTrue(session.state.value.pendingDurability)
            assertFalse(session.prepareClose())
            assertEquals("accepted", session.state.value.draft)
            failing.set(false)
            assertTrue(session.retryPersistence())
            assertFalse(session.state.value.dirty)
            NotebookLocalStore(path, "retry-durable-reader").use { reader ->
                assertEquals("accepted", reader.read(storageId)?.markdown)
            }
        }
    }

    @Test
    fun flushingAfterAnotherWriterPreservesBothHistoryAndCurrentText() = runBlocking {
        val path = Files.createTempDirectory("note-editor-flush-conflict-")
        NotebookLocalStore(path, "flush-peer").use { store ->
            val storageId = store.create("Conflict")
            val id = assertNotNull(store.read(storageId)).id
            val backend = LocalNotebookNoteBackend(store)
            val applied = assertIs<NotebookNoteSave.Saved>(backend.save(id, "", "mine")).snapshot
            assertTrue(store.replaceMarkdown(storageId, "mine", "peer after mine"))
            val result = assertIs<NotebookNoteSave.Conflict>(backend.flush(applied))
            assertEquals(applied, result.durable)
            assertEquals("peer after mine", result.current.markdown)
            assertEquals("peer after mine", store.read(storageId)?.markdown)
            NotebookLocalStore(path, "flush-conflict-reader").use { reader ->
                assertEquals("peer after mine", reader.read(storageId)?.markdown)
            }
        }
    }

    @Test
    fun missingEmptyAndMalformedIdentifiersHaveDifferentResults() = runBlocking {
        NotebookLocalStore(Files.createTempDirectory("note-editor-ids-"), "id-peer").use { store ->
            val storageId = store.create("Empty")
            val backend = LocalNotebookNoteBackend(store)
            val id = assertNotNull(store.read(storageId)).id
            assertEquals("", assertIs<NotebookNoteLoad.Found>(backend.load(id)).snapshot.markdown)
            val missing = DocumentId.generate().getBytes().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            assertEquals(NotebookNoteLoad.NotFound, backend.load(NotebookDocumentId(missing)))
            assertIs<NotebookNoteLoad.Unavailable>(backend.load(NotebookDocumentId.generate()))
            assertIs<NotebookNoteSave.Unavailable>(backend.save(NotebookDocumentId("abcd"), "", "draft"))
            assertEquals(1, store.listDocuments().size)
        }
    }

    @Test
    fun readOnlySourceDoesNotAcknowledgeSave(): Unit = runBlocking {
        NotebookLocalStore(Files.createTempDirectory("note-editor-read-only-"), "read-only-peer").use { store ->
            val storageId = store.create("Read only")
            val id = assertNotNull(store.read(storageId)).id
            store.open(storageId)!!.withDocument { document ->
                document.startTransaction().use { tx ->
                    tx.set(ObjectId.ROOT, "boardVersion", Int.MAX_VALUE)
                    tx.commit()
                }
            }.get()
            val session = NotebookNoteSession(id, LocalNotebookNoteBackend(store))
            session.refresh()
            session.edit("retained")
            assertFalse(session.save())
            assertFalse(session.prepareClose())
            assertEquals("retained", session.state.value.draft)
            assertEquals("", store.read(storageId)?.markdown)
            assertNotNull(session.state.value.error)
        }
    }
}
