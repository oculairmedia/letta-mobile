package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Block documents ride beside the drawing in the scene: last writer wins, replace keeps them, they persist. */
class CanvasDocumentBlocksTest {
    private fun set(
        id: String,
        json: String,
        lamport: Long,
        actor: String = "a",
        frame: CanvasDocumentFrame? = null,
        color: String? = null,
    ) = CanvasOp.SetDocumentOp(
        opId = "op-$lamport-$actor", actorId = actor, lamport = lamport, documentId = id, documentJson = json, frame = frame, color = color,
    )

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
    fun aWriteWithoutAFrameKeepsWhereTheNoteWasAndARemovalDropsIt() {
        val placed = CanvasDocumentFrame(x = 10f, y = 20f, width = 300f, height = 200f)
        val s1 = CanvasOpProjector.project("", listOf(set("n", "{\"v\":1}", lamport = 1, frame = placed)))
        assertEquals(placed, CanvasOpProjector.documentsOf(s1).single().frame)
        val s2 = CanvasOpProjector.project(s1, listOf(set("n", "{\"v\":2}", lamport = 2)))
        assertEquals(placed, CanvasOpProjector.documentsOf(s2).single().frame, "typing must not move the note")
        val moved = placed.copy(x = 500f)
        val s3 = CanvasOpProjector.project(s2, listOf(set("n", "{\"v\":2}", lamport = 3, frame = moved)))
        assertEquals(moved, CanvasOpProjector.documentsOf(s3).single().frame)
        val s4 = CanvasOpProjector.project(s3, listOf(remove("n", lamport = 4), set("n", "{\"v\":3}", lamport = 5)))
        assertNull(CanvasOpProjector.documentsOf(s4).single().frame, "a note recreated after removal starts unplaced")
    }

    @Test
    fun aNoteKeepsItsColourUntilRecolouredAndLosesItWhenRemoved() = runTest {
        val s1 = CanvasOpProjector.project("", listOf(set("n", "{}", lamport = 1, color = "#fde68a")))
        assertEquals("#fde68a", CanvasOpProjector.documentsOf(s1).single().color)
        val s2 = CanvasOpProjector.project(s1, listOf(set("n", "{\"v\":2}", lamport = 2)))
        assertEquals("#fde68a", CanvasOpProjector.documentsOf(s2).single().color, "typing must not recolour the note")
        val s3 = CanvasOpProjector.project(s2, listOf(remove("n", lamport = 3), set("n", "{}", lamport = 4)))
        assertNull(CanvasOpProjector.documentsOf(s3).single().color)

        val session = CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "t", canvasId = CanvasId("c3")))
        session.setDocument("n", "")
        assertNull(session.recolorDocument("missing", "#fff"))
        session.recolorDocument("n", "#bfdbfe")
        assertEquals("#bfdbfe", session.documents().single().color)
        assertNull(session.setDocument("n", "", color = "#bfdbfe"), "an unchanged colour is not written again")
    }

    @Test
    fun textStyleKeepsAcrossWritesAndDropsOnRemoval() = runTest {
        val big = CanvasTextStyle(fontScale = 1.4f, fontFamily = "serif", textColor = "#e5484d", align = "center")
        val s1 = CanvasOpProjector.project("", listOf(
            CanvasOp.SetDocumentOp(opId = "s1", actorId = "a", lamport = 1, documentId = "t", documentJson = "{}", style = big),
        ))
        assertEquals(big, CanvasOpProjector.documentsOf(s1).single().style)
        val s2 = CanvasOpProjector.project(s1, listOf(set("t", "{\"v\":2}", lamport = 2)))
        assertEquals(big, CanvasOpProjector.documentsOf(s2).single().style, "typing must not restyle the text")
        val s3 = CanvasOpProjector.project(s2, listOf(remove("t", lamport = 3), set("t", "{}", lamport = 4)))
        assertNull(CanvasOpProjector.documentsOf(s3).single().style)

        val session = CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "t", canvasId = CanvasId("c4")))
        session.setDocument("t", "")
        assertNull(session.restyleDocument("missing", big))
        session.restyleDocument("t", big)
        assertEquals(big, session.documents().single().style)
        assertNull(session.setDocument("t", "", style = big), "an unchanged style is not written again")
    }

    @Test
    fun sessionMovesANoteWithoutRewritingItsText() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val session = CanvasSession.create(store, CanvasCreateOptions(title = "t", canvasId = CanvasId("c2")))
        val frame = CanvasDocumentFrame(x = 1f, y = 2f, width = 320f, height = 240f)
        session.setDocument("n", "", frame = frame)
        assertEquals(frame, session.documents().single().frame)
        assertNull(session.setDocument("n", "", frame = frame), "the same frame and text is not written again")
        assertNull(session.moveDocument("missing", frame), "moving a note that is not there does nothing")
        session.moveDocument("n", frame.copy(x = 99f))
        val moved = session.documents().single()
        assertEquals(99f, moved.frame?.x)
        assertEquals("", moved.json)
    }

    @Test
    fun drawingsCompareByContentNotByTextSoAnAutosaveIsNotAnExternalChange() {
        val exported = """{"bgColor":"#000000ff","elements":[{"id":"b","type":"Text"},{"id":"a","type":"Text"}]}"""
        val stored = """{"elements":[{"type":"Text","id":"a"},{"type":"Text","id":"b"}],"bgColor":"#000000ff"}"""
        assertTrue(CanvasOpProjector.drawingsEqual(exported, stored), "order and formatting are not changes")
        assertTrue(!CanvasOpProjector.drawingsEqual(exported, """{"bgColor":"#ffffffff","elements":[]}"""))
        assertTrue(!CanvasOpProjector.drawingsEqual(exported, null))
        val withNote = CanvasOpProjector.project(stored, listOf(set("n", "{}", lamport = 1)))
        assertTrue(
            CanvasOpProjector.drawingsEqual(exported, CanvasOpProjector.stripMetadataForDrawBox(withNote)),
            "a note placed beside the drawing leaves the drawing unchanged",
        )
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
