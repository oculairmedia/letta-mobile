@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasLayout
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A shape's text is the shape's: centred in it, and edited with the shape still selected so the
 * menu on it is the shape's. On a phone a finger moves around the board, and panels stay small.
 */
class CanvasTouchAndShapeTextUiTest {

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Board", initialSceneJson = ""),
        )
    }

    private fun shapes(controller: DrawBoxController) =
        controller.state.value.elements.filterIsInstance<Element.Shape>()

    @Test
    fun aShapesTextIsCentredAndTheShapeStaysSelected() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Canvas board").performMouseInput { rightClick(Offset(500f, 400f)) }
        onNodeWithText("Rectangle").performClick()
        waitUntil(timeoutMillis = 5000) { shapes(controller).size == 1 }
        val shape = shapes(controller).single()
        waitUntil(timeoutMillis = 5000) { onAllNodes(isFocused() and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(isFocused() and hasSetTextAction())[0].performTextInput("Plan")
        waitForIdle()

        // The shape is still the selection, so the menu floating on it is the shape's own.
        assertEquals(setOf(shape.id), controller.state.value.selectedIds)
        onNodeWithContentDescription("Delete selection").assertExists()
        onNodeWithContentDescription("Open note large").assertDoesNotExist()

        // And the text sits in the middle of it.
        val field = onAllNodes(isFocused() and hasSetTextAction())[0].fetchSemanticsNode().boundsInRoot
        val centre = controller.state.value.viewport.worldToScreen(shape.bounds().center)
        assertTrue(abs(field.center.y - centre.y) < 12f, "the text should be vertically centred at ${centre.y}, is at ${field.center.y}")
        assertTrue(abs(field.center.x - centre.x) < 12f, "the text should be horizontally centred at ${centre.x}, is at ${field.center.x}")
    }

    @Test
    fun onAPhoneOneFingerOnOpenBoardPans() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(controller = controller, layout = CanvasLayout.COMPACT) }
        waitUntil(timeoutMillis = 5000) { controller.state.value.mode == Mode.SELECT }

        // No pan tool: dragging is how you move.
        onNodeWithContentDescription("Pan").assertDoesNotExist()
        val before = controller.state.value.viewport.offset
        onNodeWithContentDescription("Canvas board").performTouchInput {
            down(Offset(400f, 400f))
            moveTo(Offset(450f, 430f))
            moveTo(Offset(520f, 480f))
            up()
        }
        waitForIdle()
        val moved = controller.state.value.viewport.offset - before
        assertTrue(moved.x > 60f && moved.y > 40f, "the board should have followed the finger, moved by $moved")
        assertTrue(controller.state.value.selectedIds.isEmpty())
    }

    @Test
    fun twoFingersPinchToZoom() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(controller = controller, layout = CanvasLayout.COMPACT) }
        waitForIdle()

        onNodeWithContentDescription("Canvas board").performTouchInput {
            down(0, Offset(400f, 400f))
            down(1, Offset(500f, 400f))
            moveTo(0, Offset(350f, 400f))
            moveTo(1, Offset(550f, 400f))
            up(0)
            up(1)
        }
        waitForIdle()
        assertTrue(controller.state.value.viewport.scale > 1.5f, "spreading two fingers should zoom in, scale is ${controller.state.value.viewport.scale}")
        assertTrue(controller.state.value.elements.isEmpty(), "a pinch must not draw")
    }

    @Test
    fun onAPhoneThePropertyPanelOpensShort() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(controller = controller, layout = CanvasLayout.COMPACT) }

        onNodeWithContentDescription("Stroke color").performClick()
        onNodeWithContentDescription("Property panel").assertExists()
        onNodeWithContentDescription("Color red").assertExists()
        onNodeWithContentDescription("Hex color").assertDoesNotExist()
        onNodeWithContentDescription("Opacity").assertDoesNotExist()

        onNodeWithContentDescription("More options").performClick()
        onNodeWithContentDescription("Hex color").assertExists()
        onNodeWithContentDescription("Opacity").assertExists()
    }

    private fun hollowRect(id: String) = Element.Shape(
        id = id,
        shapeType = io.ak1.drawbox.domain.model.ShapeType.RECTANGLE,
        points = listOf(Offset(300f, 300f), Offset(500f, 420f)),
        strokeColor = androidx.compose.ui.graphics.Color.Black,
        strokeWidth = 2f,
    )

    @Test
    fun anOutlineOnlyShapeIsDraggedFromAnywhereInside() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session(), controller = controller) }
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.AddElement(hollowRect("r1")))
        controller.setMode(Mode.SELECT)
        waitForIdle()

        // A tap inside picks it, and it stays picked once the tap is over.
        onNodeWithContentDescription("Canvas board").performMouseInput { click(Offset(400f, 360f)) }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        assertEquals(setOf("r1"), controller.state.value.selectedIds, "a tap inside an outline-only shape should select it")

        controller.clearSelection()
        waitForIdle()
        onNodeWithContentDescription("Canvas board").performMouseInput {
            moveTo(Offset(400f, 360f))
            press()
            moveTo(Offset(430f, 380f))
            moveTo(Offset(480f, 400f))
            release()
        }
        waitForIdle()
        val moved = shapes(controller).single().bounds()
        assertEquals(380f, moved.left, 2f, "dragging from inside should move the shape, it is at $moved")
        assertEquals(340f, moved.top, 2f, "dragging from inside should move the shape, it is at $moved")
    }

    @Test
    fun aShapesTextDoesNotStopTheShapeBeingDragged() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }
        onNodeWithContentDescription("Canvas board").performMouseInput { rightClick(Offset(500f, 400f)) }
        onNodeWithText("Rectangle").performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodes(isFocused() and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(isFocused() and hasSetTextAction())[0].performTextInput("Plan")
        waitUntil(timeoutMillis = 5000) { shapes(controller).single().text == "Plan" }

        // Done typing: click away, then drag the shape by its middle, where the text is.
        onNodeWithContentDescription("Canvas board").performMouseInput { click(Offset(900f, 700f)) }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        val before = shapes(controller).single().bounds()
        onNodeWithContentDescription("Canvas board").performMouseInput {
            moveTo(Offset(500f, 400f))
            press()
            moveTo(Offset(530f, 420f))
            moveTo(Offset(560f, 440f))
            release()
        }
        waitForIdle()
        val after = shapes(controller).single().bounds()
        assertEquals(before.left + 60f, after.left, 2f, "the shape should follow a drag that starts on its text")
    }
}
