package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanvasSnapTest {
    private val frame = CanvasDocumentFrame(x = 100f, y = 200f, width = 300f, height = 100f)

    @Test
    fun anchorsAreEdgeMidpointsAndCentre() {
        val anchors = CanvasSnap.anchorsOf(frame, "note-1")
        assertEquals(
            listOf(100f to 250f, 250f to 200f, 400f to 250f, 250f to 300f, 250f to 250f),
            anchors.map { it.x to it.y },
        )
        assertEquals(listOf("left", "top", "right", "bottom", "center"), anchors.map { it.side })
        anchors.forEach { assertEquals("note-1", it.targetId) }
    }

    @Test
    fun nearestWithinRadiusWinsAndOutsideIsNull() {
        val anchors = CanvasSnap.anchorsOf(frame, "note-1")
        assertEquals("right", CanvasSnap.nearest(405f, 246f, anchors, radius = 12f)?.side)
        assertEquals("center", CanvasSnap.nearest(252f, 252f, anchors, radius = 12f)?.side)
        assertNull(CanvasSnap.nearest(420f, 246f, anchors, radius = 12f))
    }

    @Test
    fun anchorOnFollowsTheSide() {
        assertEquals(100f to 250f, CanvasSnap.anchorOn(frame, "left"))
        assertEquals(250f to 300f, CanvasSnap.anchorOn(frame, "bottom"))
        assertEquals(250f to 250f, CanvasSnap.anchorOn(frame, "center"))
        val moved = frame.copy(x = 500f)
        assertEquals(800f to 250f, CanvasSnap.anchorOn(moved, "right"))
    }

    @Test
    fun arrowBindingsPersistPerConnectorWithLww() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val session = CanvasSession.create(store = store, options = CanvasCreateOptions(title = "Bound", initialSceneJson = ""))
        val binding = CanvasArrowBinding(end = CanvasEndBinding("note-1", "left"))
        session.bindArrow("arrow-1", binding)
        assertEquals(binding, session.arrowBindings()["arrow-1"])
        assertNull(session.bindArrow("arrow-1", binding), "same binding is a no-op")
        session.bindArrow("arrow-1", CanvasArrowBinding())
        assertEquals(CanvasArrowBinding(), session.arrowBindings()["arrow-1"])

        // Bindings survive drawing saves and never reach DrawBox.
        session.applyLocalScene("""{"bgColor":"#ffffffff","elements":[{"id":"arrow-1","type":"Shape","zIndex":1,"points":["0.0,0.0","10.0,10.0"],"strokeColor":"#000000ff","strokeWidth":2.0,"shapeType":"ARROW","modifiedAt":1}]}""")
        assertEquals(1, session.arrowBindings().size)
        assertEquals(false, CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty()).contains("_arrowBindings"))

        val base = CanvasOpProjector.emptySceneJson()
        val a = CanvasOp.SetArrowBindingOp("b1", "peerA", 5, "arrow-1", CanvasArrowBinding(start = CanvasEndBinding("n1", "top")))
        val b = CanvasOp.SetArrowBindingOp("b2", "peerB", 6, "arrow-1", CanvasArrowBinding(end = CanvasEndBinding("n2", "left")))
        val ab = CanvasOpProjector.arrowBindingsOf(CanvasOpProjector.project(base, listOf(a, b)))
        val ba = CanvasOpProjector.arrowBindingsOf(CanvasOpProjector.project(base, listOf(b, a)))
        assertEquals(ab, ba)
        assertEquals("n2", ab["arrow-1"]?.end?.documentId)
    }
}
