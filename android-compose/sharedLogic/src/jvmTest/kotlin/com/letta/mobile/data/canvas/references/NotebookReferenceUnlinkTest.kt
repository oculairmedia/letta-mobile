package com.letta.mobile.data.canvas.references

import com.letta.mobile.data.canvas.NotebookDocument
import com.letta.mobile.data.canvas.NotebookItem
import com.letta.mobile.data.canvas.NotebookLocalStore
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.automerge.repo.DocumentId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotebookReferenceUnlinkTest {
    private val opaque = NotebookItem("opaque", "vendor.future", version = 17, payloadJson = """{"preserve":[1,{"unknown":true}]}""")
    private val chat = NotebookItem("chat", "chat", conversationId = "independent-chat", payloadJson = """{"future":"keep"}""")

    @Test
    fun unlinkSurvivesRestartTargetSurvivesAndUnknownItemsAreUntouched() {
        val directory = Files.createTempDirectory("notebook-reference-unlink-")
        val saved = NotebookLocalStore(directory, "reference-unlink-peer").use { store ->
            prepareAndUnlink(store)
        }
        NotebookLocalStore(directory, "reference-unlink-peer").use { store ->
            with(saved) {
                val reopened = assertNotNull(store.read(source))
                assertEquals(expectedSource, reopened)
                assertEquals(setOf("keep-reference", "opaque", "chat"), reopened.items.map { it.id }.toSet())
                assertEquals(opaque, reopened.items.single { it.id == opaque.id })
                assertEquals(chat, reopened.items.single { it.id == chat.id })
                assertEquals(targetBefore.id, reopened.items.single { it.id == "keep-reference" }.targetDocumentId)
                assertEquals(targetBefore, store.read(target))
                assertContentEquals(targetBytes, store.inspect(target) { it.save() })
                assertTrue(store.listDocuments().containsAll(listOf(source, target)))
                store.inspect(source) { document ->
                    assertEquals(itemsObject, (document.get(ObjectId.ROOT, "items").orElseThrow() as AmValue.Map).id)
                    assertFalse(document.get(itemsObject, "unlink-me").isPresent)
                    val vendor = (document.get(ObjectId.ROOT, "vendor").orElseThrow() as AmValue.Map).id
                    assertContentEquals(byteArrayOf(0, -1, 2), (document.get(vendor, "bytes").orElseThrow() as AmValue.Bytes).value)
                }
            }
        }
    }

    private fun prepareAndUnlink(store: NotebookLocalStore): UnlinkedNotebook {
        val source = store.create("Source")
        val target = store.create("Target")
        store.insertMarkdown(source, 0, "source Markdown")
        store.insertMarkdown(target, 0, "# Target survives")
        val targetBefore = assertNotNull(store.read(target))
        store.putItem(source, NotebookItem("unlink-me", "reference", targetDocumentId = targetBefore.id))
        store.putItem(source, NotebookItem("keep-reference", "reference", targetDocumentId = targetBefore.id))
        store.putItem(source, opaque)
        store.putItem(source, chat)
        store.inspect(source) { document ->
            document.startTransaction().use { tx ->
                val vendor = tx.set(ObjectId.ROOT, "vendor", ObjectType.MAP)
                tx.set(vendor, "bytes", byteArrayOf(0, -1, 2))
                tx.commit()
            }
        }
        val itemsObject = store.inspect(source) { (it.get(ObjectId.ROOT, "items").orElseThrow() as AmValue.Map).id }
        val before = assertNotNull(store.read(source))
        val heads = store.inspect(source) { it.heads }
        val targetBytes = store.inspect(target) { it.save() }

        store.removeItem(source, "unlink-me")

        val expectedSource = before.copy(items = before.items.filterNot { it.id == "unlink-me" })
        assertEquals(expectedSource, store.read(source))
        store.inspect(source) { document ->
            val patches = document.diff(heads, document.heads)
            assertTrue(patches.isNotEmpty())
            assertTrue(patches.all { it.obj == itemsObject }, "only the source items map changes")
            assertFalse(document.get(itemsObject, "unlink-me").isPresent)
        }
        assertContentEquals(targetBytes, store.inspect(target) { it.save() })
        assertTrue(store.persistCanvas(source), "await source unlink durability before closing")
        assertTrue(store.persistCanvas(target), "await target history durability before closing")
        return UnlinkedNotebook(source, target, expectedSource, targetBefore, targetBytes, itemsObject)
    }

    private fun <T> NotebookLocalStore.inspect(id: DocumentId, block: (Document) -> T): T =
        assertNotNull(open(id)).withDocument(block).get(10, TimeUnit.SECONDS)
}

private class UnlinkedNotebook(
    val source: DocumentId,
    val target: DocumentId,
    val expectedSource: NotebookDocument,
    val targetBefore: NotebookDocument,
    val targetBytes: ByteArray,
    val itemsObject: ObjectId,
)
