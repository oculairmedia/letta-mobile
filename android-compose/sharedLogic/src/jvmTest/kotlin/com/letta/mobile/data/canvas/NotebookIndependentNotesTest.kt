package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.notes.LocalNotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.references.NotebookReferenceAccess
import com.letta.mobile.data.canvas.references.NotebookReferenceContent
import com.letta.mobile.data.canvas.references.NotebookReferenceLoad
import com.letta.mobile.data.canvas.references.NotebookReferenceResolver
import com.letta.mobile.data.canvas.references.NotebookReferenceSession
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import org.automerge.AmValue
import org.automerge.ObjectId
import org.automerge.repo.DocumentId

class NotebookIndependentNotesTest {
    @Test
    fun referenceStillResolvesAfterTargetStorageIdIsRetiredAndRequiresAuthorization() = runBlocking {
        val directory = Files.createTempDirectory("notebook-reference-compaction-")
        val source: DocumentId
        val target: DocumentId
        NotebookLocalStore(directory, "reference-compaction-peer").use { store ->
            source = store.create("Source")
            target = store.create("Target")
            store.insertMarkdown(target, 0, "Retained body")
            store.putItem(source, NotebookItem("embed", "reference", targetDocumentId = NotebookDocumentId(target.stableKey())))
            assertTrue(store.persistCanvas(source))
            assertTrue(store.persistCanvas(target))
        }
        val oldKey = target.stableKey()
        val moved = NotebookHistoryArchive(directory).compactToNewDocument(
            oldKey, 1_000L, NotebookDocumentIndex(directory), 1_024L,
        )
        NotebookLocalStore(directory, "reference-compaction-peer").use { store ->
            val reference = store.read(source)!!.items.single()
            assertEquals(oldKey, reference.targetDocumentId?.value)
            assertNull(store.open(target), "retired storage must stay inaccessible")
            assertEquals(moved.newKey, store.listDocuments().single { it != source }.stableKey())
            var mayReadTarget = false
            var targetReads = 0
            val resolver = NotebookReferenceResolver(
                authorize = { id ->
                    if (id.value == oldKey && !mayReadTarget) NotebookReferenceAccess.DENIED else NotebookReferenceAccess.ALLOWED
                },
                readAuthorized = { id ->
                    if (id.value == oldKey) targetReads++
                    val storageId = DocumentId.fromBytes(id.value.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                    store.read(storageId)?.let(NotebookReferenceLoad::Found) ?: NotebookReferenceLoad.Missing
                },
            )
            val session = NotebookReferenceSession(NotebookDocumentId(source.stableKey()), resolver)
            session.refresh()
            assertEquals(NotebookReferenceContent.Placeholder.DENIED, session.state.value.root!!.children.single().content)
            assertEquals(0, targetReads, "denied targets must never reach storage")
            mayReadTarget = true
            session.refresh()
            assertEquals(NotebookReferenceContent.Markdown("Target", "Retained body"), session.state.value.root!!.children.single().content)
            assertEquals(1, targetReads)
            val backend = LocalNotebookNoteBackend(store)
            val loaded = assertIs<NotebookNoteLoad.Found>(backend.load(NotebookDocumentId(oldKey)))
            assertEquals(oldKey, loaded.snapshot.id.value)
            assertEquals("Retained body", loaded.snapshot.markdown)
            assertIs<NotebookNoteSave.Saved>(backend.save(NotebookDocumentId(oldKey), "Retained body", "Edited body"))
            assertEquals("Edited body", store.read(target)?.markdown)
        }
        val movedAgain = NotebookHistoryArchive(directory).compactToNewDocument(
            moved.newKey, 2_000L, NotebookDocumentIndex(directory), 1_024L,
        )
        NotebookLocalStore(directory, "reference-compaction-peer").use { store ->
            assertEquals(movedAgain.newKey, store.listDocuments().single { it != source }.stableKey())
            assertNull(store.open(target))
            assertEquals(oldKey, store.read(target)?.id?.value)
            assertEquals("Edited body", store.read(target)?.markdown)
        }
    }

    @Test
    fun independentMarkdownNotesKeepTheirIdentityAndTextAcrossRestart() {
        val directory = Files.createTempDirectory("notebook-independent-notes-")
        val firstId: DocumentId
        val secondId: DocumentId

        NotebookLocalStore(directory, "independent-notes-peer").use { store ->
            firstId = store.create("First")
            secondId = store.create("Second")
            assertNotEquals(firstId, secondId)

            store.insertMarkdown(firstId, 0, "First body")
            store.insertMarkdown(secondId, 0, "Second body")
            assertTrue(store.persistCanvas(firstId), "first note changes reach durable storage")
            assertTrue(store.persistCanvas(secondId), "second note changes reach durable storage")

            // A note has its own Automerge identity; opening it does not need a CanvasId mapping.
            assertNull(store.canvasDocument(firstId))
            assertNull(store.canvasDocument(secondId))

            store.insertMarkdown(firstId, "First body".length, " updated")
            assertEquals("First body updated", store.read(firstId)?.markdown)
            assertEquals("Second body", store.read(secondId)?.markdown)
            assertTrue(store.persistCanvas(firstId), "the edit reaches durable storage before close")
        }

        NotebookLocalStore(directory, "independent-notes-peer").use { reopened ->
            val first = assertNotNull(reopened.read(firstId))
            val second = assertNotNull(reopened.read(secondId))

            assertEquals(firstId.stableKey(), first.id.value)
            assertEquals(secondId.stableKey(), second.id.value)
            assertNotEquals(first.id, second.id)
            assertEquals("First body updated", first.markdown)
            assertEquals("Second body", second.markdown)
            assertEquals(listOf(firstId, secondId), reopened.listDocuments())
            assertNull(reopened.canvasDocument(firstId))
            assertNull(reopened.canvasDocument(secondId))
        }
    }

    @Test
    fun markdownEditPreservesOpaqueRootAndItemPayloadAcrossDurableRestart() {
        val directory = Files.createTempDirectory("notebook-opaque-note-fields-")
        val id: DocumentId
        val opaqueRoot = """{"vendor":"future-client","flags":["keep","all"]}"""
        val opaquePayload = """{"unknown":{"nested":true},"bytes":[0,1,255]}"""
        val item = NotebookItem(
            id = "future-item",
            kind = "vendor.future-item",
            version = 7,
            payloadJson = opaquePayload,
        )

        NotebookLocalStore(directory, "opaque-notes-peer").use { store ->
            id = store.create("Opaque fields")
            store.open(id)!!.withDocument { document ->
                document.startTransaction().use { transaction ->
                    transaction.set(ObjectId.ROOT, "vendorMetadata", opaqueRoot)
                    transaction.commit()
                }
            }.get(10, TimeUnit.SECONDS)
            store.putItem(id, item)

            assertEquals(opaqueRoot, store.rootString(id, "vendorMetadata"))
            assertEquals(item, store.read(id)?.items?.single())

            store.insertMarkdown(id, 0, "Initial note")
            assertEquals("Initial note", store.read(id)?.markdown)
            assertEquals(opaqueRoot, store.rootString(id, "vendorMetadata"))
            assertEquals(item, store.read(id)?.items?.single())
            assertTrue(store.persistCanvas(id), "the edited note and opaque fields reach durable storage")
        }

        NotebookLocalStore(directory, "opaque-notes-peer").use { reopened ->
            assertEquals("Initial note", assertNotNull(reopened.read(id)).markdown)
            assertEquals(opaqueRoot, reopened.rootString(id, "vendorMetadata"))
            assertEquals(item, reopened.read(id)?.items?.single())
            assertNull(reopened.canvasDocument(id), "the standalone note has no canvas document")
        }
    }

    private fun NotebookLocalStore.rootString(id: DocumentId, key: String): String =
        open(id)!!.withDocument { document ->
            (document.get(ObjectId.ROOT, key).orElseThrow() as AmValue.Str).value
        }.get(10, TimeUnit.SECONDS)

    private fun DocumentId.stableKey(): String = getBytes().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
