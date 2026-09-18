package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanvasBatchMoveTest {
    @Test
    fun moveDocumentsIsOneRevisionWithOneOpPerNote() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val session = CanvasSession.create(store = store, options = CanvasCreateOptions(title = "Group", initialSceneJson = ""))
        val a = CanvasDocumentFrame(100f, 100f, 200f, 100f)
        val b = CanvasDocumentFrame(400f, 100f, 200f, 100f)
        session.setDocument("a", "{}", frame = a)
        session.setDocument("b", "{}", frame = b)
        val before = session.document.value!!.revision

        val moved = session.moveDocuments(
            mapOf(
                "a" to a.copy(x = 130f, y = 140f),
                "b" to b.copy(x = 430f, y = 140f),
                "missing" to a,
            ),
        )
        assertEquals(before + 1, moved?.revision)
        val frames = session.documents().associate { it.id to it.frame }
        assertEquals(CanvasDocumentFrame(130f, 140f, 200f, 100f), frames["a"])
        assertEquals(CanvasDocumentFrame(430f, 140f, 200f, 100f), frames["b"])
        assertEquals(2, session.documents().size)

        // Nothing changes: no op, no revision.
        assertNull(session.moveDocuments(mapOf("a" to frames.getValue("a")!!)))
        assertEquals(before + 1, session.document.value!!.revision)
    }
}
