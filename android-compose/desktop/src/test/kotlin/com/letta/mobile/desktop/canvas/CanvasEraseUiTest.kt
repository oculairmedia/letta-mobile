@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasConversationOptions
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
     * REPRODUCES AN OPEN BUG - see letta-mobile-ys5zi.
     *
     * Erasing removes the element from the board and it returns when the stroke is released. The
     * sibling test below proves deleting a SELECTION does not, so whatever brings it back is on
     * the erase path specifically, not the shared export-and-write that follows both.
     *
     * The eraser hits a shape the way a finger does - on its outline, not in the empty middle of
     * an unfilled rectangle - so the gesture runs down the left edge.
     */
    @Test
    fun anErasedElementDoesNotComeBackWhenTheStrokeIsReleased() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        controller.importPath(drawing)
        waitUntil(timeoutMillis = 10_000) { controller.state.value.elements.size == 2 }
        waitUntil(timeoutMillis = 10_000) { session.hasElement("rect-1") }

        // An erase gesture, as the hand makes it: down, across the shape's edge, up.
        onNodeWithContentDescription("Eraser").performClick()
        controller.onIntent(Intent.BeginErase)
        controller.onIntent(Intent.EraseAt(Offset(10f, 40f), 12f))
        controller.onIntent(Intent.EraseAt(Offset(10f, 50f), 12f))
        waitUntil(timeoutMillis = 5000) { "rect-1" !in ids(controller) }
        controller.onIntent(Intent.EndErase)

        // Release is where it came back. Everything the board does then - settle, export, write -
        // has to leave the element gone, and has to KEEP it gone.
        waitUntil(timeoutMillis = 10_000) { !session.hasElement("rect-1") }
        repeat(20) {
            waitForIdle()
            assertTrue("rect-1" !in ids(controller), "the erased element came back on release")
            assertTrue(!session.hasElement("rect-1"), "the erased element came back in the session")
        }
        assertTrue("rect-2" in ids(controller), "the eraser took a shape it never touched")
    }

    /**
     * The same erase, made with the hand that makes it: a drag down the shape's left edge on the
     * board itself, so DrawBox's own pointer handling and the board's Final-pass eraser both run.
     * Driving the intents directly skips both, and that is where "it comes back on release" would
     * live if the intents alone cannot show it.
     */
    @Test
    fun erasingWithADragLeavesTheElementGone() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        controller.importPath(drawing)
        waitUntil(timeoutMillis = 10_000) { controller.state.value.elements.size == 2 }
        waitUntil(timeoutMillis = 10_000) { session.hasElement("rect-1") }

        onNodeWithContentDescription("Eraser").performClick()
        waitForIdle()
        val viewport = controller.state.value.viewport
        val from = viewport.worldToScreen(Offset(10f, 20f))
        val to = viewport.worldToScreen(Offset(10f, 80f))
        onNodeWithContentDescription("Canvas board").performMouseInput {
            moveTo(from)
            press()
            moveTo(Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f))
            moveTo(to)
            release()
        }

        waitUntil(timeoutMillis = 10_000) { "rect-1" !in ids(controller) }
        waitUntil(timeoutMillis = 10_000) { !session.hasElement("rect-1") }
        repeat(20) {
            waitForIdle()
            assertTrue("rect-1" !in ids(controller), "the erased element came back on release")
            assertTrue(!session.hasElement("rect-1"), "the erased element came back in the session")
        }
    }

    /**
     * The board people actually meet: one that was drawn on yesterday and opened again today.
     *
     * Every write is settled last-writer-wins against the provenance already in the stored scene,
     * so a session that opens an existing board and starts counting from zero makes its own edits
     * older than the board - the erase is discarded by the projector and the shape is still there
     * the next time the scene is read back. See letta-mobile-ys5zi.
     */
    @Test
    fun erasingOnABoardThatWasOpenedAgainStillRemovesTheElement() = runComposeUiTest {
        val store = InMemoryCanvasDocumentStore()
        val first = runBlocking {
            CanvasSession.create(store = store, options = CanvasCreateOptions(title = "Board", initialSceneJson = ""))
        }
        // Yesterday's drawing, written through the session so it carries that session's provenance.
        runBlocking { first.applyLocalScene(drawing) }

        // Today: the same board, a new session over the stored scene.
        val session = runBlocking { requireNotNull(CanvasSession.open(store, first.canvasId, CanvasConversationOptions())) }
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        waitUntil(timeoutMillis = 10_000) { controller.state.value.elements.size == 2 }

        onNodeWithContentDescription("Eraser").performClick()
        controller.onIntent(Intent.BeginErase)
        controller.onIntent(Intent.EraseAt(Offset(10f, 40f), 12f))
        waitUntil(timeoutMillis = 5000) { "rect-1" !in ids(controller) }
        controller.onIntent(Intent.EndErase)

        waitUntil(timeoutMillis = 10_000) { !session.hasElement("rect-1") }
        repeat(20) {
            waitForIdle()
            assertTrue("rect-1" !in ids(controller), "the erased element came back on the re-opened board")
            assertTrue(!session.hasElement("rect-1"), "the erased element came back in the stored board")
        }
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

    /** True when [id] is still a live element of the drawing, tombstones aside. */
    private fun CanvasSession.hasElement(id: String): Boolean =
        com.letta.mobile.data.canvas.CanvasOpProjector.stripMetadataForDrawBox(sceneJsonOrEmpty())
            .contains("\"id\":\"" + id + "\"")
}
