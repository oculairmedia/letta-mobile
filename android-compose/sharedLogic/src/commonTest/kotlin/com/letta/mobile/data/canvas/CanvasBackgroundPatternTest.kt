package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasBackgroundPatternTest {

    @Test
    fun patternPersistsOnTheSceneRootAndReloads() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val session = CanvasSession.create(
            store = store,
            options = CanvasCreateOptions(title = "Grid Board", initialSceneJson = ""),
        )
        assertNull(session.backgroundPattern(), "a new board has no pattern")

        val dots = CanvasBackgroundPattern(kind = CanvasBackgroundPattern.DOTS, spacing = 16f, colorHex = "#3b82f6")
        session.setBackgroundPattern(dots)
        assertEquals(dots, session.backgroundPattern())
        // A second identical write is a no-op, not another revision.
        val before = session.document.value?.revision
        assertNull(session.setBackgroundPattern(dots))
        assertEquals(before, session.document.value?.revision)

        // The pattern survives a drawing save: the differ only touches elements and bgColor.
        session.applyLocalScene("""{"bgColor":"#f7f3eaff","elements":[{"id":"e1","type":"Shape","zIndex":1,"points":["0.0,0.0","10.0,10.0"],"strokeColor":"#000000ff","strokeWidth":2.0,"shapeType":"RECTANGLE","modifiedAt":1}]}""")
        assertEquals(dots, session.backgroundPattern())
        assertTrue(session.sceneJsonOrEmpty().contains("\"bgColor\":\"#f7f3eaff\""))

        // What DrawBox is handed never carries the pattern bookkeeping.
        val forDrawBox = CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty())
        assertTrue(!forDrawBox.contains("_bgPattern"), forDrawBox)

        // Reloading from the store sees the same pattern.
        val reopened = CanvasSession(canvasId = session.canvasId, store = store)
        reopened.load()
        assertEquals(dots, reopened.backgroundPattern())
    }

    @Test
    fun patternWritesConvergeByLamportThenActor() {
        val base = CanvasOpProjector.emptySceneJson()
        val grid = CanvasOp.SetBackgroundPatternOp("p1", "peerA", 7, CanvasBackgroundPattern(CanvasBackgroundPattern.GRID, 32f, "#000000"))
        val lines = CanvasOp.SetBackgroundPatternOp("p2", "peerB", 8, CanvasBackgroundPattern(CanvasBackgroundPattern.LINES, 64f, "#ffffff"))
        val ab = CanvasOpProjector.project(base, listOf(grid, lines))
        val ba = CanvasOpProjector.project(base, listOf(lines, grid))
        assertEquals(CanvasOpProjector.backgroundPatternOf(ab), CanvasOpProjector.backgroundPatternOf(ba))
        assertEquals(CanvasBackgroundPattern.LINES, CanvasOpProjector.backgroundPatternOf(ab)?.kind)
    }
}
