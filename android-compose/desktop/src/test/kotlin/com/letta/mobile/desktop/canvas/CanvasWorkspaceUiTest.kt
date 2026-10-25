@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.canvas.CanvasSamples
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.bounds
import kotlin.test.Test

class CanvasWorkspaceUiTest {

    @Test
    fun canvasWorkspace_colorsReachDrawBoxAndTheBoard() = runComposeUiTest {
        val controller = io.ak1.drawbox.presentation.viewmodel.DrawBoxController(
            io.ak1.drawbox.presentation.reducer.Reducer(io.ak1.drawbox.domain.usecase.UseCase()),
        )
        setContent {
            CanvasWorkspace(controller = controller)
        }

        // The rail's stroke swatch opens the one master control; a preset and then a hex value
        // both reach the tool's stroke colour through the same picker.
        onNodeWithContentDescription("Stroke color").performClick()
        onNodeWithContentDescription("Property panel").assertExists()
        onNodeWithContentDescription("Color red").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.strokeColor == androidx.compose.ui.graphics.Color(0xFFE5484D) }
        onNodeWithContentDescription("Hex color").performTextReplacement("#123456")
        waitUntil(timeoutMillis = 5000) { controller.state.value.strokeColor == androidx.compose.ui.graphics.Color(0xFF123456) }
        // The red preset picked a moment ago is offered again as a recent colour.
        onNodeWithContentDescription("Color recent 1").assertExists()
        onNodeWithContentDescription("Color recent 1").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.strokeColor == androidx.compose.ui.graphics.Color(0xFFE5484D) }
        // Width, opacity and dash for the tool, from the same control.
        onNodeWithContentDescription("Width Thick").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.strokeWidth == 8f }
        onNodeWithContentDescription("Stroke Dashed").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.currentItemStrokeStyle == io.ak1.drawbox.domain.model.StrokeStyle.DASHED }
        onNodeWithContentDescription("Close properties").performClick()
        onAllNodesWithContentDescription("Property panel").assertCountEquals(0)

        // A closed-shape tool brings up fill, outline and corner radius; picking a fill reaches
        // the tool settings.
        onNodeWithContentDescription("Rectangle").performClick()
        onNodeWithContentDescription("Properties").performClick()
        onNodeWithContentDescription("Target fill").performClick()
        onNodeWithContentDescription("Color blue").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.currentItemFillColor == androidx.compose.ui.graphics.Color(0xFF3B82F6) }
        onNodeWithContentDescription("Outline on").performClick()
        waitUntil(timeoutMillis = 5000) { !controller.state.value.currentItemStrokeEnabled }
        onNodeWithContentDescription("Corner radius").assertExists()
        onNodeWithContentDescription("Close properties").performClick()

        // A drawn rectangle, once selected, is the control's target: fill, width and opacity
        // land on the element (and opacity on the tool default too, as DrawBox keeps it).
        controller.importPath(
            """{"bgColor":"#ffffffff","elements":[{"id":"rect-1","type":"Shape","zIndex":1,
            "points":["10.0,10.0","120.0,90.0"],"strokeColor":"#000000ff","strokeWidth":4.0,
            "shapeType":"RECTANGLE","modifiedAt":1}]}""",
        )
        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.size == 1 }
        onNodeWithContentDescription("Select").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.mode == io.ak1.drawbox.domain.model.Mode.SELECT }
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.SelectAt(androidx.compose.ui.geometry.Offset(60f, 10f), 12f))
        waitUntil(timeoutMillis = 5000) { controller.state.value.selectedIds == setOf("rect-1") }
        onNodeWithContentDescription("Properties").performClick()
        onNodeWithContentDescription("Target fill").performClick()
        onNodeWithContentDescription("Color green").performClick()
        onNodeWithContentDescription("Width Bold").performClick()
        waitUntil(timeoutMillis = 5000) {
            val rect = controller.state.value.elements.filterIsInstance<io.ak1.drawbox.domain.model.Element.Shape>().single()
            rect.fillColor == androidx.compose.ui.graphics.Color(0xFF22C55E) && rect.strokeWidth == 14f
        }
        onNodeWithContentDescription("Opacity").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.5f) }
        waitUntil(timeoutMillis = 5000) { controller.state.value.opacity == 0.5f }
        onNodeWithContentDescription("Close properties").performClick()
        controller.clearSelection()

        // Board background from the overflow menu.
        onNodeWithContentDescription("More").performClick()
        onNodeWithContentDescription("Background paper").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.bgColor == androidx.compose.ui.graphics.Color(0xFFF7F3EA) }
    }

    @Test
    fun canvasWorkspace_fitToContent_showsEveryElement() = runComposeUiTest {
        val controller = io.ak1.drawbox.presentation.viewmodel.DrawBoxController(
            io.ak1.drawbox.presentation.reducer.Reducer(io.ak1.drawbox.domain.usecase.UseCase()),
        )
        setContent { CanvasWorkspace(controller = controller, initialJson = CanvasSamples.buildCycleJson) }
        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.size == 15 }
        onNodeWithContentDescription("Zoom in").performClick()
        onNodeWithText("125%").assertExists()

        onNodeWithContentDescription("Fit to content").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.viewport.scale != 1.25f }
        val board = onNodeWithContentDescription("Canvas board").fetchSemanticsNode().size
        val viewport = controller.state.value.viewport
        controller.state.value.elements.forEach { element ->
            val r = element.bounds()
            val tl = viewport.worldToScreen(androidx.compose.ui.geometry.Offset(r.left, r.top))
            val br = viewport.worldToScreen(androidx.compose.ui.geometry.Offset(r.right, r.bottom))
            kotlin.test.assertTrue(tl.x >= 0f && tl.y >= 0f && br.x <= board.width && br.y <= board.height, "${element.id} at $tl..$br outside $board")
        }
        // Double-clicking the percentage returns to 100%.
        onNodeWithContentDescription("Zoom level").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5000) { controller.state.value.viewport.scalePercent == 100 }
    }

    @Test
    fun canvasWorkspace_rendersAndRespondsToButtonClicks() = runComposeUiTest {
        setContent {
            CanvasWorkspace(
                initialJson = CanvasSamples.buildCycleJson,
            )
        }

        // The rarely used commands live behind the overflow menu, not on the board.
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Import Build Cycle").assertExists()
        onNodeWithText("Import Daily Loop").assertExists()
        onNodeWithText("Clear").assertExists()
        onNodeWithText("Export JSON").assertExists()
        onNodeWithText("Export SVG").assertExists()

        // Clear and assert state updates
        onNodeWithText("Clear").performClick()
        onNodeWithText("Elements: 0 | Cleared canvas").assertExists()

        // Import Build Cycle and assert elements load
        openMenuItem("Import Build Cycle")
        onNodeWithText("Elements: 15 | Imported Build Cycle sample").assertExists()

        // Import Daily Loop and assert elements load
        openMenuItem("Import Daily Loop")
        onNodeWithText("Imported Daily Loop sample", substring = true).assertExists()
    }

}
