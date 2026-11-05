package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.notes.LocalNotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.model.CanvasId
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A notebook-backed canvas and its note are one document under two names (letta-mobile-n2stx.2.1, B2):
 * each resolves to the other, and an edit through one view leaves the other view's part untouched.
 */
class CanvasNotebookIdentityTest {
    @Test
    fun aCanvasAndItsNoteResolveToEachOther() = runBlocking {
        notebookStore { store, identity, _ ->
            val session = CanvasSession.create(store, CanvasCreateOptions(title = "Plan"))
            val notebook = assertNotNull(identity.notebookOf(session.canvasId))

            assertTrue(notebook.value.length == 32 && notebook.value.all { it in '0'..'9' || it in 'a'..'f' })
            assertEquals(session.canvasId, identity.canvasOf(notebook))
        }
    }

    @Test
    fun theNoteViewAndTheCanvasViewEditOneDocumentWithoutCopying() = runBlocking {
        notebookStore { store, identity, notebooks ->
            val scene = """{"elements":[{"id":"box","type":"rectangle"}],"_future":{"kept":true}}"""
            val session = CanvasSession.create(store, CanvasCreateOptions(title = "Plan", initialSceneJson = scene))
            val notebook = assertNotNull(identity.notebookOf(session.canvasId))
            val notes = LocalNotebookNoteBackend(notebooks)
            val before = assertIs<NotebookNoteLoad.Found>(notes.load(notebook)).snapshot
            assertEquals("Plan", before.title)

            assertIs<NotebookNoteSave.Saved>(notes.save(notebook, before.markdown, "Ship the bridge"))
            val canvas = assertNotNull(store.get(session.canvasId))
            assertEquals(session.canvasId, canvas.id)
            assertTrue(canvas.sceneJson.contains("\"kept\":true"))
            assertTrue(canvas.sceneJson.contains("\"box\""))

            session.applyLocalScene("""{"elements":[{"id":"box","type":"rectangle","x":40}],"_future":{"kept":true}}""")
            assertTrue(store.awaitDurableSave(session.canvasId))
            val after = assertIs<NotebookNoteLoad.Found>(notes.load(notebook)).snapshot
            assertEquals("Ship the bridge", after.markdown)
            assertEquals(notebook, identity.notebookOf(session.canvasId))
        }
    }

    @Test
    fun anIdThatNamesNoStoredNotebookResolvesToNothing() = runBlocking {
        notebookStore { _, identity, _ ->
            assertNull(identity.notebookOf(CanvasId("canvas-nowhere")))
            assertNull(identity.canvasOf(NotebookDocumentId("notebook-not-a-storage-id")))
            assertNull(identity.canvasOf(NotebookDocumentId("00112233445566778899aabbccddeeff")))
        }
    }

    private suspend fun notebookStore(
        block: suspend (NotebookCanvasDocumentStore, NotebookCanvasIdentity, NotebookLocalStore) -> Unit,
    ) {
        NotebookLocalStore(Files.createTempDirectory("canvas-notebook-identity-"), "identity-peer").use { notebooks ->
            block(NotebookCanvasDocumentStore(notebooks), NotebookCanvasIdentity(notebooks), notebooks)
        }
    }
}
