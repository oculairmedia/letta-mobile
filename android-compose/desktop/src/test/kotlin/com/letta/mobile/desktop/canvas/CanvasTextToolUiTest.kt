@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.BuiltinFontFamilyKeys
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import io.ak1.drawbox.domain.usecase.UseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Text on the board is one of the drawing's own elements.
 *
 * It was a note in disguise before: a block document in a card, measured by us, which meant two
 * kinds of text on one board and a box that argued with its own contents while you typed. DrawBox
 * has a text element that places, measures, wraps and hit tests itself; the host's only part is
 * the caret, and the size of the type is set from the same bar every other property is.
 */
class CanvasTextToolUiTest {

    /** Past the double-tap timeout, so a single tap is settled as one. */
    private val DOUBLE_TAP_GRACE_MS = 1000L


    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Board", initialSceneJson = ""),
        )
    }

    private fun texts(controller: DrawBoxController) =
        controller.state.value.elements.filterIsInstance<Element.Text>()

    @Test
    fun theTextToolPlacesADrawingElementAndOpensACaretInIt() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Text").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.mode == Mode.TEXT }

        onNodeWithContentDescription("Canvas board").performMouseInput { click(Offset(220f, 180f)) }
        // A tap only becomes a tap once it can no longer become a double tap, and the test clock
        // does not get there on its own.
        mainClock.advanceTimeBy(DOUBLE_TAP_GRACE_MS)

        // DrawBox inserts the element; the board answers with a caret in it.
        waitUntil(timeoutMillis = 5000) { texts(controller).size == 1 }
        waitUntil(timeoutMillis = 5000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }

        onAllNodes(hasSetTextAction()).onFirst().performTextInput("hello world")
        waitUntil(timeoutMillis = 5000) { texts(controller).single().text.contains("hello world") }

        // It is a drawing element, so it is saved as part of the drawing rather than as a note.
        waitUntil(timeoutMillis = 10_000) { session.sceneJsonOrEmpty().contains("hello world") }
        assertTrue(session.documents().isEmpty(), "the text tool made a note document, not a drawing element")
    }

    /** The two buttons on the selection bar: the size control you reach for without opening anything. */
    @Test
    fun theBarStepsTheTextSizeUpAndDown() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Text").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.mode == Mode.TEXT }
        onNodeWithContentDescription("Canvas board").performMouseInput { click(Offset(220f, 180f)) }
        mainClock.advanceTimeBy(DOUBLE_TAP_GRACE_MS)
        waitUntil(timeoutMillis = 5000) { texts(controller).size == 1 }
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("step me")
        waitUntil(timeoutMillis = 5000) { texts(controller).single().text.contains("step me") }

        onNodeWithContentDescription("Select").performClick()
        val placed = texts(controller).single()
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.SelectAt(placed.topLeft, 12f))
        waitUntil(timeoutMillis = 5000) { controller.state.value.selectedIds == setOf(placed.id) }
        val started = texts(controller).single().fontSize

        onNodeWithContentDescription("Larger text").performClick()
        waitUntil(timeoutMillis = 5000) { texts(controller).single().fontSize > started }
        val grown = texts(controller).single().fontSize

        onNodeWithContentDescription("Smaller text").performClick()
        waitUntil(timeoutMillis = 5000) { texts(controller).single().fontSize < grown }
        assertEquals("step me", texts(controller).single().text, "stepping the size rewrote the words")
    }

    @Test
    fun theTextSizeControlSetsTheSizeOfTheSelectedText() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Text").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.mode == Mode.TEXT }
        onNodeWithContentDescription("Canvas board").performMouseInput { click(Offset(220f, 180f)) }
        // A tap only becomes a tap once it can no longer become a double tap, and the test clock
        // does not get there on its own.
        mainClock.advanceTimeBy(DOUBLE_TAP_GRACE_MS)
        waitUntil(timeoutMillis = 5000) { texts(controller).size == 1 }
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("size me")
        waitUntil(timeoutMillis = 5000) { texts(controller).single().text.contains("size me") }

        // Pick it up as a drawing element, the way any other element is picked up.
        onNodeWithContentDescription("Select").performClick()
        val placed = texts(controller).single()
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.SelectAt(placed.topLeft, 12f))
        waitUntil(timeoutMillis = 5000) { controller.state.value.selectedIds == setOf(placed.id) }

        // The size lives on the property panel, with every other property.
        onNodeWithContentDescription("Properties").performClick()
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithContentDescription("Text size XL").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Text size XL").performClick()
        waitUntil(timeoutMillis = 5000) { texts(controller).single().fontSize == 56f }
        assertEquals("size me", texts(controller).single().text, "setting the size rewrote the words")

        // The face and the edge it is set against come from the same panel.
        onNodeWithContentDescription("Font Serif").performClick()
        waitUntil(timeoutMillis = 5000) { texts(controller).single().fontFamilyKey == BuiltinFontFamilyKeys.SERIF }
        onNodeWithContentDescription("Align center").performClick()
        waitUntil(timeoutMillis = 5000) { texts(controller).single().alignment == TextAlignment.CENTER }
        assertEquals(56f, texts(controller).single().fontSize, "the face reset the size")
    }
}
