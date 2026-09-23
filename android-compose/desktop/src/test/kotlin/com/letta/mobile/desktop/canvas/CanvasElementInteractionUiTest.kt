@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.longClick
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
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Working with an element the way Miro and Obsidian do: a shape is somewhere to type, its menu
 * floats on it, and a long press (or a right click) opens a menu where the finger is.
 */
class CanvasElementInteractionUiTest {

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Board", initialSceneJson = ""),
        )
    }

    private fun rectangle(id: String, left: Float, top: Float) = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(left, top), Offset(left + 200f, top + 120f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
    )

    private fun shapes(controller: DrawBoxController) =
        controller.state.value.elements.filterIsInstance<Element.Shape>()

    @Test
    fun aShapeAddedFromTheBoardMenuTakesTypingStraightAway() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Canvas board").performMouseInput { rightClick(Offset(420f, 360f)) }
        onNodeWithText("Rectangle").performClick()
        waitUntil(timeoutMillis = 5000) { shapes(controller).size == 1 }
        val shape = shapes(controller).single()
        assertTrue(shape.bounds().contains(Offset(420f, 360f)), "the shape went where the menu was opened, got ${shape.bounds()}")

        // Its label exists and has the caret: typing goes into the shape.
        val labelId = "label-${shape.id}"
        waitUntil(timeoutMillis = 5000) { session.documents().any { it.id == labelId } }
        waitUntil(timeoutMillis = 5000) { onAllNodes(isFocused() and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(isFocused() and hasSetTextAction())[0].performTextInput("Plan")
        waitUntil(timeoutMillis = 10_000) { session.documents().single { it.id == labelId }.json.contains("Plan") }
    }

    @Test
    fun drawingAShapeOpensItsText() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Rectangle").performClick()
        onNodeWithContentDescription("Canvas board").performMouseInput {
            moveTo(Offset(300f, 300f))
            press()
            moveTo(Offset(380f, 350f))
            moveTo(Offset(460f, 400f))
            release()
        }
        waitUntil(timeoutMillis = 5000) { shapes(controller).size == 1 }
        val labelId = "label-${shapes(controller).single().id}"
        waitUntil(timeoutMillis = 5000) { session.documents().any { it.id == labelId } }
        waitUntil(timeoutMillis = 5000) { onAllNodes(isFocused() and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aLongPressOnAShapeOpensItsMenu() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }
        controller.onIntent(Intent.AddElement(rectangle("r1", 300f, 300f)))
        waitForIdle()

        onNodeWithContentDescription("Canvas board").performTouchInput { longClick(Offset(400f, 360f)) }
        onNodeWithText("Edit text").assertExists()
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 5000) { shapes(controller).isEmpty() }
    }

    @Test
    fun aLongPressOnEmptyBoardOffersWhatCanBeAdded() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session(), controller = controller) }

        onNodeWithContentDescription("Canvas board").performTouchInput { longClick(Offset(500f, 400f)) }
        onNodeWithText("Note").assertExists()
        onNodeWithText("Text").assertExists()
        onNodeWithText("Circle").performClick()
        waitUntil(timeoutMillis = 5000) { shapes(controller).any { it.shapeType == ShapeType.CIRCLE } }
        // A long press is not a tap: the draw tool left no dot behind.
        assertTrue(controller.state.value.elements.none { it is Element.Path }, "the long press also drew")
    }

    @Test
    fun theSelectionBarFloatsOnTheSelection() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session(), controller = controller) }
        controller.onIntent(Intent.AddElement(rectangle("r1", 400f, 420f)))
        controller.setMode(Mode.SELECT)
        // On its outline: an unfilled shape is picked by its stroke.
        controller.selectAt(Offset(400f, 480f), 4f)
        waitForIdle()

        val bar = onNodeWithContentDescription("Delete selection").fetchSemanticsNode().boundsInRoot
        assertTrue(bar.bottom <= 420f, "the bar should sit above the shape (top 420), its bottom is at ${bar.bottom}")
        assertTrue(bar.left >= 250f && bar.right <= 750f, "the bar should be over the shape, got $bar")
        onNodeWithContentDescription("Edit text").assertExists()
    }
}
