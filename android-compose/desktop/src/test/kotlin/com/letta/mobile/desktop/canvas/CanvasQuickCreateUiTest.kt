@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performTextInput
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Miro's quick-create targets: one press adds a joined, empty copy beside the selection. */
class CanvasQuickCreateUiTest {

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "Board", initialSceneJson = ""))
    }

    @Test
    fun theTargetAddsAJoinedShapeAndTakesTyping() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session(), controller = controller) }
        controller.onIntent(
            Intent.AddElement(
                Element.Shape(
                    id = "a", shapeType = ShapeType.RECTANGLE,
                    points = listOf(Offset(200f, 200f), Offset(360f, 300f)),
                    strokeColor = Color.Black, strokeWidth = 2f, text = "First",
                ),
            ),
        )
        controller.setMode(Mode.SELECT)
        controller.selectAt(Offset(200f, 250f), 4f)
        waitForIdle()

        onNodeWithContentDescription("Add to the right").performClick()
        fun rects() = controller.state.value.elements.filterIsInstance<Element.Shape>().filter { it.shapeType == ShapeType.RECTANGLE }
        waitUntil(timeoutMillis = 5000) { rects().size == 2 }
        val next = rects().first { it.id != "a" }
        assertEquals("", next.text, "the new shape starts empty")
        assertEquals(160f, next.bounds().width, 1f, "the same size as the one it came from")
        assertTrue(next.bounds().left > 360f, "to the right of it, got ${next.bounds()}")
        assertEquals(250f, next.bounds().center.y, 1f, "level with it")

        val arrow = controller.state.value.elements.filterIsInstance<Element.Shape>().single { it.shapeType == ShapeType.ARROW }
        assertEquals("a", arrow.startBinding, "the arrow is bound to the first shape")
        assertEquals(next.id, arrow.endBinding, "and to the new one")

        // The new shape is selected with the caret in it.
        assertEquals(setOf(next.id), controller.state.value.selectedIds)
        waitUntil(timeoutMillis = 5000) { onAllNodes(isFocused() and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(isFocused() and hasSetTextAction())[0].performTextInput("Second")
        waitUntil(timeoutMillis = 5000) { rects().first { it.id == next.id }.text == "Second" }
    }

    @Test
    fun noTargetsWithoutASingleShapeSelected() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session(), controller = controller) }
        waitForIdle()
        onNodeWithContentDescription("Add to the right").assertDoesNotExist()
    }

    @Test
    fun aNoteQuickCreatesTheNextNote() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Canvas board").performMouseInput { rightClick(Offset(300f, 300f)) }
        onNodeWithText("Note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        val first = session.documents().single()

        onNodeWithContentDescription("Add below").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 2 }
        val second = session.documents().first { it.id != first.id }
        assertEquals(first.color, second.color, "the same kind of note")
        assertTrue(second.frame!!.y > first.frame!!.y + first.frame!!.height, "below the first")
        waitUntil(timeoutMillis = 5000) {
            controller.state.value.elements.any { it is Element.Shape && it.shapeType == ShapeType.ARROW }
        }
    }
}
