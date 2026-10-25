package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.automerge.AmValue
import org.automerge.ObjectId
import org.automerge.repo.DocumentId

class NotebookIndependentNotesTest {
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
