@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import io.ak1.drawbox.domain.usecase.UseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A deleted element has to stay deleted.
 *
 * The board round-trips through the session constantly: the drawing is exported on a debounce and
 * written as ops, and a revision arriving from the session is imported back into the controller.
 * A deletion that survives the export but loses the race with an import comes back on screen,
 * which is indistinguishable from the delete never having worked.
 */
class CanvasDeleteElementUiTest {

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Board", initialSceneJson = ""),
        )
    }

    private val drawing = """{"bgColor":"#ffffffff","elements":[
        {"id":"rect-1","type":"Shape","zIndex":1,"points":["10.0,10.0","120.0,90.0"],
         "strokeColor":"#000000ff","strokeWidth":4.0,"shapeType":"RECTANGLE","modifiedAt":1},
        {"id":"rect-2","type":"Shape","zIndex":2,"points":["200.0,10.0","320.0,90.0"],
         "strokeColor":"#000000ff","strokeWidth":4.0,"shapeType":"RECTANGLE","modifiedAt":1}]}"""

    @Test
    fun aDeletedElementDoesNotComeBack() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        // Two shapes on the board, saved through the session the way drawing them would.
        controller.importPath(drawing)
        waitUntil(timeoutMillis = 10_000) { controller.state.value.elements.size == 2 }
        waitUntil(timeoutMillis = 10_000) { session.hasElement("rect-2") }
        val before = controller.state.value.elements
        val doomed = before.first { it.id == "rect-1" }

        controller.onIntent(Intent.DeleteElement(doomed.id))
        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.none { it.id == doomed.id } }

        // The autosave debounce, the export it triggers and the write that follows all have to
        // settle before this means anything - and then it has to STAY true, which is the part that
        // fails when an import brings the element back.
        // The scene keeps a tombstone for a removed element on purpose - that is what stops a
        // peer re-adding it - so this asks whether the element is still IN the drawing, not
        // whether its id appears anywhere in the json.
        waitUntil(timeoutMillis = 10_000) { !session.hasElement(doomed.id) }
        repeat(20) {
            waitForIdle()
            assertTrue(
                controller.state.value.elements.none { it.id == doomed.id },
                "the deleted element came back on the board",
            )
            assertTrue(!session.hasElement(doomed.id), "the deleted element came back in the session")
        }
        assertEquals(before.size - 1, controller.state.value.elements.size)
    }

    /** True when [id] is still a live element of the drawing, tombstones aside. */
    private fun CanvasSession.hasElement(id: String): Boolean =
        com.letta.mobile.data.canvas.CanvasOpProjector.stripMetadataForDrawBox(sceneJsonOrEmpty())
            .contains("\"id\":\"" + id + "\"")
}
