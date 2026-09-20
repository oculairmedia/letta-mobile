@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An erased element stays erased, and so does a deleted one.
 *
 * Both were reported coming back "on release" - the moment the gesture ends and the board settles,
 * exports and writes itself to the session. That is the only thing that happens at release, so it
 * is where the resurrection has to be.
 */
class CanvasEraseUiTest {

    private val drawing = """{"bgColor":"#ffffffff","elements":[
        {"id":"rect-1","type":"Shape","zIndex":1,"points":["10.0,10.0","120.0,90.0"],
         "strokeColor":"#000000ff","strokeWidth":4.0,"shapeType":"RECTANGLE","modifiedAt":1},
        {"id":"rect-2","type":"Shape","zIndex":2,"points":["200.0,10.0","320.0,90.0"],
         "strokeColor":"#000000ff","strokeWidth":4.0,"shapeType":"RECTANGLE","modifiedAt":1}]}"""

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Board", initialSceneJson = ""),
        )
    }

    private fun ids(controller: DrawBoxController) = controller.state.value.elements.map { it.id }

    /**
     * REPRODUCES AN OPEN BUG - see letta-mobile-ys5zi - so it is ignored rather than deleted.
     *
     * Erasing removes the element from the board and it returns when the stroke is released. The
     * sibling test below proves deleting a SELECTION does not, so whatever brings it back is on
     * the erase path specifically, not the shared export-and-write that follows both.
     *
     * Remove the @Ignore to work on it; it fails at the point where the session should no longer
     * contain the element.
     */
    @Ignore
    @Test
    fun anErasedElementDoesNotComeBackWhenTheStrokeIsReleased() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        controller.importPath(drawing)
        waitUntil(timeoutMillis = 10_000) { controller.state.value.elements.size == 2 }
        waitUntil(timeoutMillis = 10_000) { session.sceneJsonOrEmpty().contains("rect-1") }

        // An erase gesture, as the hand makes it: down, across the shape, up.
        onNodeWithContentDescription("Eraser").performClick()
        controller.onIntent(Intent.BeginErase)
        controller.onIntent(Intent.EraseAt(Offset(60f, 50f), 12f))
        waitUntil(timeoutMillis = 5000) { "rect-1" !in ids(controller) }
        controller.onIntent(Intent.EndErase)

        // Release is where it came back. Everything the board does then - settle, export, write -
        // has to leave the element gone.
        repeat(40) { waitForIdle() }
        waitUntil(timeoutMillis = 10_000) { !session.sceneJsonOrEmpty().contains("\"id\":\"rect-1\"") }
        repeat(40) { waitForIdle() }
        assertTrue("rect-1" !in ids(controller), "the erased element came back on release")
    }

    @Test
    fun aDeletedSelectionDoesNotComeBack() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        controller.importPath(drawing)
        waitUntil(timeoutMillis = 10_000) { controller.state.value.elements.size == 2 }
        waitUntil(timeoutMillis = 10_000) { session.sceneJsonOrEmpty().contains("rect-1") }

        onNodeWithContentDescription("Select").performClick()
        controller.onIntent(Intent.SelectAt(Offset(60f, 10f), 12f))
        waitUntil(timeoutMillis = 5000) { controller.state.value.selectedIds == setOf("rect-1") }
        onNodeWithContentDescription("Delete selection").performClick()
        waitUntil(timeoutMillis = 5000) { "rect-1" !in ids(controller) }

        repeat(40) { waitForIdle() }
        assertTrue("rect-1" !in ids(controller), "the deleted element came back")
    }
}
