@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.canvas.CanvasLayout
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Finger gestures on the board, driven as real touch the way the desktop now delivers fingers.
 * Each is a behaviour someone relies on; these keep it from quietly going away.
 */
class CanvasTouchGesturesUiTest {

    private fun box(id: String, area: Rect) = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(area.topLeft, area.bottomRight),
        strokeColor = Color.Red,
        strokeWidth = 4f,
    )

    private fun ComposeUiTest.board(
        controller: DrawBoxController,
        desktop: Boolean,
        vararg shapes: Element.Shape,
    ): SemanticsNodeInteraction {
        setContent {
            CanvasWorkspace(
                controller = controller,
                layout = if (desktop) CanvasLayout.EXPANDED else CanvasLayout.COMPACT,
                longPressDrawsSelectionBox = desktop,
            )
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        shapes.forEach { controller.onIntent(Intent.AddElement(it)) }
        controller.setMode(Mode.SELECT)
        controller.clearSelection()
        waitForIdle()
        return onNodeWithContentDescription("Canvas board")
    }

    private fun ComposeUiTest.settle() {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    @Test
    fun aDoubleTapOnOpenBoardEasesTheBoardInTwice() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        val node = board(controller, desktop = true)
        val before = controller.state.value.viewport.scale
        node.performTouchInput { doubleClick(Offset(300f, 300f)) }
        settle()
        assertEquals(before * 2f, controller.state.value.viewport.scale, 0.01f)
    }

    @Test
    fun aDoubleTapOnAShapeLeavesTheZoomForItsText() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        val node = board(controller, desktop = true, box("a", Rect(200f, 200f, 400f, 400f)))
        val before = controller.state.value.viewport.scale
        node.performTouchInput { doubleClick(Offset(300f, 300f)) }
        settle()
        assertEquals(before, controller.state.value.viewport.scale, 0.001f)
    }

    @Test
    fun aLongPressOnAnElementAddsItToTheSelection() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        val node = board(controller, desktop = true, box("a", Rect(100f, 100f, 200f, 200f)), box("b", Rect(300f, 100f, 400f, 200f)))
        controller.selectIds(setOf("a"))
        waitForIdle()
        node.performTouchInput { longClick(Offset(350f, 150f)) }
        settle()
        assertEquals(setOf("a", "b"), controller.state.value.selectedIds)
    }

    @Test
    fun aLongPressOnAnElementAddsToTheSelectionOnAPhoneToo() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        val node = board(controller, desktop = false, box("a", Rect(100f, 100f, 200f, 200f)), box("b", Rect(300f, 100f, 400f, 200f)))
        controller.selectIds(setOf("a"))
        waitForIdle()
        node.performTouchInput { longClick(Offset(350f, 150f)) }
        settle()
        assertEquals(setOf("a", "b"), controller.state.value.selectedIds)
    }

    @Test
    fun onTheDesktopALongPressThenDragOnOpenBoardSelectsWhatTheBoxCovers() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        val node = board(
            controller,
            desktop = true,
            box("a", Rect(100f, 100f, 200f, 200f)),
            box("b", Rect(300f, 100f, 400f, 200f)),
            box("far", Rect(700f, 700f, 760f, 760f)),
        )
        node.performTouchInput {
            down(Offset(60f, 60f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(250f, 150f))
            moveTo(Offset(450f, 250f))
            up()
        }
        settle()
        assertEquals(setOf("a", "b"), controller.state.value.selectedIds)
        assertNull(controller.state.value.marqueeRect, "the box goes once it has selected")
    }

    @Test
    fun onAPhoneALongPressOnOpenBoardDoesNotDrawABox() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        val node = board(controller, desktop = false, box("a", Rect(100f, 100f, 200f, 200f)))
        node.performTouchInput {
            down(Offset(60f, 60f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(250f, 250f))
            up()
        }
        settle()
        assertTrue(controller.state.value.selectedIds.isEmpty(), "the phone's long press opens its menu instead")
    }
}
