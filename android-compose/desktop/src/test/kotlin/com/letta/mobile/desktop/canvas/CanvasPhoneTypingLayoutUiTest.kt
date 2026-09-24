@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasLayout
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** Typing into a new shape on a phone: one slim bar rides on the keyboard, the tool bar steps aside. */
class CanvasPhoneTypingLayoutUiTest {
    @Test
    fun aShapeBeingTypedIntoGetsTheSlimBarAndNoToolBar() = runComposeUiTest {
        val session = runBlocking {
            CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "Board", initialSceneJson = ""))
        }
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller, layout = CanvasLayout.COMPACT) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        onNodeWithContentDescription("Add").performClick()
        onNodeWithText("Rectangle").performClick()
        waitUntil(timeoutMillis = 5000) {
            controller.state.value.elements.any { it is Element.Shape && it.shapeType == ShapeType.RECTANGLE }
        }
        waitForIdle()

        // The caret is in the new shape: the tool bar is gone and the slim bar is there instead.
        onNodeWithContentDescription("Add").assertDoesNotExist()
        onNodeWithContentDescription("Change shape").assertExists()
        onNodeWithContentDescription("Smaller text").assertExists()
        onNodeWithContentDescription("Larger text").assertExists()
        // And it is at the foot, where the keyboard will be, not over the shape mid-board.
        val board = onRoot().getBoundsInRoot()
        val bar = onNodeWithContentDescription("Change shape").getBoundsInRoot()
        assertTrue(bar.top.value > board.bottom.value * 0.75f, "the bar is at ${bar.top} on a board ${board.bottom} tall")
        // Colour and style are the tool bar's; the phone bar does not carry a second copy.
        onNodeWithContentDescription("Properties").assertDoesNotExist()

        // Everything else is one menu away.
        onNodeWithContentDescription("More shape actions").performClick()
        onNodeWithText("Bring to front").assertExists()
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.none { it is Element.Shape && it.shapeType == ShapeType.RECTANGLE } }
    }
}
