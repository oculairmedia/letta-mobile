package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Block documents ride beside the drawing in the scene: last writer wins, replace keeps them, they persist. */
class CanvasDocumentBlocksTest {
    private fun set(id: String, json: String, lamport: Long, actor: String = "a") =
        CanvasOp.SetDocumentOp(opId = "op-$lamport-$actor", actorId = actor, lamport = lamport, documentId = id, documentJson = json)

    private fun remove(id: String, lamport: Long, actor: String = "a") =
        CanvasOp.RemoveDocumentOp(opId = "rm-$lamport-$actor", actorId = actor, lamport = lamport, documentId = id)

    @Test
    fun newerDocumentWriteWinsAndOlderLoses() {
        val s1 = CanvasOpProjector.project("", listOf(set("notes", "{\"v\":1}", lamport = 5)))
        val s2 = CanvasOpProjector.project(s1, listOf(set("notes", "{\"v\":2}", lamport = 7)))
        val s3 = CanvasOpProjector.project(s2, listOf(set("notes", "{\"v\":stale}", lamport = 6)))
        assertEquals("{\"v\":2}", CanvasOpProjector.documentsOf(s3).single().json)
        assertEquals(s2, s3)
    }

    @Test
    fun removalTombstonesTheDocumentUntilANewerWrite() {
        val s1 = CanvasOpProjector.project("", listOf(set("notes", "{}", lamport = 5)))
        val s2 = CanvasOpProjector.project(s1, listOf(remove("notes", lamport = 8)))
        assertTrue(CanvasOpProjector.documentsOf(s2).isEmpty())
        val s3 = CanvasOpProjector.project(s2, listOf(set("notes", "{\"late\":1}", lamport = 7)))
        assertTrue(CanvasOpProjector.documentsOf(s3).isEmpty(), "an older write must not resurrect a removed document")
        val s4 = CanvasOpProjector.project(s3, listOf(set("notes", "{\"back\":1}", lamport = 9)))
        assertEquals("{\"back\":1}", CanvasOpProjector.documentsOf(s4).single().json)
    }

    @Test
    fun replaceSceneKeepsDocumentsUnlessItBringsItsOwn() {
        val withDoc = CanvasOpProjector.project("", listOf(set("notes", "{\"kept\":true}", lamport = 1)))
        val replaced = CanvasOpProjector.project(
            withDoc,
            listOf(CanvasOp.ReplaceSceneOp(opId = "r", actorId = "agent", lamport = 2, sceneJson = """{"bgColor":"#000000ff","elements":[]}""")),
        )
        assertEquals("{\"kept\":true}", CanvasOpProjector.documentsOf(replaced).single().json)
        assertTrue(CanvasOpProjector.stripMetadataForDrawBox(replaced).contains("_documents").not(), "DrawBox never sees the documents")
    }

    @Test
    fun sessionRoundTripsTheDocumentThroughTheStore() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val session = CanvasSession.create(store, CanvasCreateOptions(title = "t", canvasId = CanvasId("c1")))
        session.setDocument("notes", "{\"blocks\":[]}")
        assertEquals("{\"blocks\":[]}", session.documents().single().json)
        assertNull(session.setDocument("notes", "{\"blocks\":[]}"), "an unchanged document is not written again")

        val reopened = CanvasSession.open(store, CanvasId("c1"))!!
        assertEquals("{\"blocks\":[]}", reopened.documents().single().json)
    }
}
