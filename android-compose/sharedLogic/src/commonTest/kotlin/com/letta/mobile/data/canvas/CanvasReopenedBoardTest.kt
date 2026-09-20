package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A board that is opened again has to be editable.
 *
 * Every write is stamped with the session's lamport clock and settled last-writer-wins against the
 * provenance already in the scene. A session that opens a canvas someone (including the same
 * person, yesterday) has already written to therefore has to start its clock ABOVE what the scene
 * holds - otherwise its first edits are older than what they are editing, the projector keeps the
 * existing value, and the change is dropped without an error anywhere.
 */
class CanvasReopenedBoardTest {

    private fun drawing(vararg ids: String): String =
        """{"bgColor":"#ffffffff","elements":[""" + ids.joinToString(",") { id ->
            """{"id":"$id","type":"Shape","zIndex":1,"points":["10.0,10.0","120.0,90.0"],
               "strokeColor":"#000000ff","strokeWidth":4.0,"shapeType":"RECTANGLE","modifiedAt":1}"""
        } + "]}"

    private fun String.hasElement(id: String): Boolean =
        CanvasOpProjector.stripMetadataForDrawBox(this).contains("\"id\":\"$id\"")

    @Test
    fun anElementErasedOnAReopenedBoardIsActuallyRemoved() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val opLog = InMemoryCanvasOpLog()

        // A board drawn on, the way a first sitting leaves it.
        val first = CanvasSession.create(
            store = store,
            options = CanvasCreateOptions(title = "Board", opLog = opLog, initialSceneJson = ""),
        )
        first.applyLocalScene(drawing("rect-1", "rect-2"))
        assertTrue(first.sceneJsonOrEmpty().hasElement("rect-1"))

        // The same board, opened again: a new session over the stored scene.
        val reopened = requireNotNull(CanvasSession.open(store, first.canvasId, CanvasConversationOptions(opLog = opLog)))
        reopened.load()
        assertTrue(reopened.sceneJsonOrEmpty().hasElement("rect-1"), "the stored board did not come back")

        // Erase one shape: the board exports what is left and the session writes the difference.
        reopened.applyLocalScene(drawing("rect-2"))
        assertFalse(reopened.sceneJsonOrEmpty().hasElement("rect-1"), "the erased element survived the write")
        assertFalse(
            requireNotNull(store.get(first.canvasId)).sceneJson.hasElement("rect-1"),
            "the erased element is still in the stored board",
        )
    }

    /** The same, for a note: removing one on a reopened board has to stick. */
    @Test
    fun aNoteRemovedOnAReopenedBoardIsActuallyRemoved() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val opLog = InMemoryCanvasOpLog()

        val first = CanvasSession.create(
            store = store,
            options = CanvasCreateOptions(title = "Board", opLog = opLog, initialSceneJson = ""),
        )
        first.setDocument("note-1", """{"blocks":[]}""")
        assertTrue(first.documents().any { it.id == "note-1" })

        val reopened = requireNotNull(CanvasSession.open(store, first.canvasId, CanvasConversationOptions(opLog = opLog)))
        reopened.load()
        assertTrue(reopened.documents().any { it.id == "note-1" }, "the stored note did not come back")

        reopened.removeDocument("note-1")
        assertFalse(reopened.documents().any { it.id == "note-1" }, "the removed note came back")
    }
}
