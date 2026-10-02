package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.automerge.ObjectId
import org.automerge.repo.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The board layout is versioned in `ROOT.boardVersion`, so a build that meets a layout it does
 * not understand refuses to write it (and says so) instead of writing back what it cannot see.
 */
class NotebookBoardLayoutTest {
    private val withNotes = """{"elements":[{"id":"a","type":"Path"}],"_documents":{"n1":{"json":"{}"}}}"""

    private fun layout(store: NotebookLocalStore, id: DocumentId): Long =
        store.open(id)!!.withDocument { NotebookBoardStorage.layoutVersion(it) }.get(5, TimeUnit.SECONDS)

    @Test
    fun movingObjectFieldsOutOfTheBoardStringBumpsTheLayout() = runBlocking {
        NotebookLocalStore(Files.createTempDirectory("notebook-layout-bump-"), "layout-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            store.upsert(CanvasDocument(CanvasId("plain"), title = "Plain", sceneJson = """{"elements":[{"id":"a"}]}"""))
            val plain = notebooks.listDocuments().single()
            assertEquals(1L, layout(notebooks, plain))
            store.upsert(CanvasDocument(CanvasId("plain"), title = "Plain", revision = 1, sceneJson = withNotes))
            assertEquals(NotebookBoardStorage.LAYOUT_VERSION.toLong(), layout(notebooks, plain))
        }
    }

    @Test
    fun aNewerLayoutIsReadButNeverWrittenAndTheBoardSaysWhy() = runBlocking {
        NotebookLocalStore(Files.createTempDirectory("notebook-layout-newer-"), "layout-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val canvasId = CanvasId("future")
            store.upsert(CanvasDocument(canvasId, title = "Future", sceneJson = withNotes))
            val id = notebooks.listDocuments().single()
            // A later build's peer moved the board on to a layout this build does not know.
            notebooks.open(id)!!.withDocument { document ->
                document.startTransaction().use { tx ->
                    tx.set(ObjectId.ROOT, "boardVersion", NotebookBoardStorage.LAYOUT_VERSION + 1)
                    tx.commit()
                }
            }.get(5, TimeUnit.SECONDS)
            val before = store.get(canvasId)!!

            // Reading works, and puts a READ_ONLY fault on this board.
            val fault = notebooks.faults.faults.value.single { it.kind == CanvasStorageFault.Kind.READ_ONLY }
            assertTrue(fault.isError)
            assertEquals(canvasId, fault.canvasId)
            assertEquals(listOf(id), notebooks.checkLayouts())

            // Every write path refuses; the canvas store does not throw at the board.
            store.upsert(before.copy(revision = before.revision + 1, sceneJson = """{"elements":[]}"""))
            assertEquals(false, store.upsertIfRevision(before.copy(revision = before.revision + 1, sceneJson = """{"elements":[]}"""), before.revision))
            assertFailsWith<NotebookReadOnlyException> { notebooks.insertMarkdown(id, 0, "x") }
            assertFailsWith<NotebookReadOnlyException> { notebooks.removeBoardElement(id, "a") }
            assertEquals(before, store.get(canvasId))
            assertEquals("", notebooks.read(id)!!.markdown)
            // One fault for the document, however many writes were refused.
            assertEquals(1, notebooks.faults.faults.value.count { it.kind == CanvasStorageFault.Kind.READ_ONLY })
            // Other boards are unaffected.
            store.upsert(CanvasDocument(CanvasId("today"), title = "Today", sceneJson = """{"elements":[{"id":"b"}]}"""))
            assertTrue(store.get(CanvasId("today"))!!.sceneJson.contains("\"b\""))
        }
    }
}
