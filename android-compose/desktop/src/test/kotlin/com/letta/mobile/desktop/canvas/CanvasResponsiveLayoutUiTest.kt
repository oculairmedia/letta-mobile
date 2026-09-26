@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.canvas.CanvasLayout
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.test.Test

/**
 * The board picks its chrome by the room it has: the tool rail and zoom pill on a desktop-sized
 * board, one bottom tool bar with undo and redo moved up top on a phone-sized one.
 */
class CanvasResponsiveLayoutUiTest {

    @Test
    fun aWideBoardKeepsTheRailAndTheZoomButtons() = runComposeUiTest {
        setContent { CanvasWorkspace() }

        onNodeWithContentDescription("Rectangle").assertExists()
        onNodeWithContentDescription("Zoom in").assertExists()
        onNodeWithContentDescription("Add").assertDoesNotExist()
    }

    @Test
    fun aPhoneWidthBoardGetsTheCompactLayoutOnItsOwn() = runComposeUiTest {
        setContent {
            Box(modifier = Modifier.width(360.dp)) { CanvasWorkspace() }
        }

        onNodeWithContentDescription("Add").assertIsDisplayed()
        onNodeWithContentDescription("Rectangle").assertDoesNotExist()
        onNodeWithContentDescription("Zoom in").assertDoesNotExist()
        onNodeWithContentDescription("Undo").assertExists()
        onNodeWithContentDescription("Redo").assertExists()
    }

    @Test
    fun theCompactBarAddsEveryShapeThroughItsAddMenu() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(controller = controller, layout = CanvasLayout.COMPACT) }

        // The bar keeps only the tools a hand switches between; shapes are added, not drawn.
        onNodeWithContentDescription("Rectangle").assertDoesNotExist()
        onNodeWithContentDescription("Add").performClick()
        onNodeWithText("Triangle").performClick()
        waitUntil(timeoutMillis = 5000) {
            controller.state.value.elements.any { it is Element.Shape && it.shapeType == ShapeType.TRIANGLE }
        }

        onNodeWithContentDescription("Draw").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.mode == Mode.PEN }
    }

    @Test
    fun theCompactOverflowCarriesZoom() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(controller = controller, layout = CanvasLayout.COMPACT) }

        val before = controller.state.value.viewport.scale
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Zoom in (100%)").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.viewport.scale > before }
        onNodeWithText("Fit to content").assertExists()
    }
}

class CanvasCompactFitOnOpenUiTest {
    @Test
    fun aPhoneBoardOpensFittedToItsContent() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent {
            Box(modifier = Modifier.width(360.dp)) {
                CanvasWorkspace(controller = controller, initialJson = com.letta.mobile.ui.canvas.CanvasSamples.buildCycleJson)
            }
        }
        waitUntil(timeoutMillis = 5000) { controller.state.value.viewport.scale < 1f }
    }
}
