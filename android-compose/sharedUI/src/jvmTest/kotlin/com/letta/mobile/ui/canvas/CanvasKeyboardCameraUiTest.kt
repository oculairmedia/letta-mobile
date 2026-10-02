@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.theme.LocalReducedMotion
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * letta-mobile-bglj6.1: typing into a note at the foot of a phone's board. The keyboard rises,
 * the Touch chat bar rides up on it, and the camera pans the note into the band above both (and
 * the board's own tool bar); when the keyboard goes, the camera goes back where it was. A desktop
 * window has no keyboard inset, so the keyboard is stood in for (LocalCanvasImeInsets) and the bar's
 * inset stands on it as ChatSurface's does.
 */
class CanvasKeyboardCameraUiTest {
    /** A keyboard whose height the test sets. */
    private class StandInKeyboard : WindowInsets {
        var bottomPx by mutableIntStateOf(0)
        override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int = 0
        override fun getTop(density: Density): Int = 0
        override fun getRight(density: Density, layoutDirection: LayoutDirection): Int = 0
        override fun getBottom(density: Density): Int = bottomPx
    }

    private class Board(val session: CanvasSession, val controller: DrawBoxController, val keyboard: StandInKeyboard) {
        var chromeBottom by mutableStateOf(BAR)

        /** The keyboard at [heightPx], and the bar standing on it. */
        fun keyboardAt(heightPx: Int) {
            keyboard.bottomPx = heightPx
            chromeBottom = BAR + heightPx.dp
        }
    }

    private fun ComposeUiTest.showBoard(reducedMotion: Boolean = false): Board {
        val session = runBlocking {
            CanvasSession.create(
                store = InMemoryCanvasDocumentStore(),
                options = CanvasCreateOptions(title = "Board", initialSceneJson = ""),
            ).also { it.setDocument(NOTE, "", frame = CanvasDocumentFrame(0f, 0f, NOTE_W, NOTE_H), color = "#FFF59D") }
        }
        val board = Board(session, DrawBoxController(Reducer(UseCase())), StandInKeyboard())
        setContent {
            // As the Touch page lays it out (ChatSurface's TouchCanvasWithChat): the board above the
            // bar but for its rounded top, the bar's inset measured from the window's foot.
            val clear = PaddingValues(bottom = board.chromeBottom - REACH)
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().padding(clear).consumeWindowInsets(clear)) {
                        CompositionLocalProvider(
                            LocalCanvasChromeBottomInset provides board.chromeBottom,
                            LocalCanvasImeInsets provides board.keyboard,
                        ) {
                            CanvasWorkspace(session = board.session, controller = board.controller, showTitle = false, layout = CanvasLayout.COMPACT)
                        }
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Note $NOTE").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        // Pixels and dp are the same here, which the keyboard and bar sizes below rely on.
        assertEquals(1f, density.density)
        return board
    }

    /** The note's top and bottom on screen, in px from the window's top (the board's top is there). */
    private fun Board.noteOnScreen(): Pair<Float, Float> {
        val viewport = controller.state.value.viewport
        return viewport.worldToScreen(Offset(0f, 0f)).y to viewport.worldToScreen(Offset(0f, NOTE_H)).y
    }

    /** Pans the board so the note sits at its left, its foot just above the bar at rest. */
    private fun ComposeUiTest.parkNoteAtTheFoot(board: Board, windowHeightPx: Float) {
        runOnIdle {
            val viewport = board.controller.state.value.viewport
            val topLeft = viewport.worldToScreen(Offset.Zero)
            val bottom = viewport.worldToScreen(Offset(0f, NOTE_H)).y
            val footTarget = windowHeightPx - BAR.value - PARKED_ABOVE_BAR
            board.controller.panBy(Offset(LEFT - topLeft.x, footTarget - bottom))
        }
        waitForIdle()
    }

    private fun ComposeUiTest.typeIntoTheNote() {
        onAllNodesWithContentDescription("Note $NOTE")[0].performClick()
        waitForIdle()
    }

    @Test
    fun aNoteAtTheFootIsPannedAboveTheKeyboardAndTheBarAndBackWhenItGoes() = runComposeUiTest {
        val board = showBoard()
        val window = onRoot().fetchSemanticsNode().size.height.toFloat()
        parkNoteAtTheFoot(board, window)
        typeIntoTheNote()
        val before = board.controller.state.value.viewport
        val scale = before.scale

        // The keyboard slides in over a few frames; the camera follows each one.
        for (height in listOf(KEYBOARD / 4, KEYBOARD / 2, KEYBOARD)) {
            runOnIdle { board.keyboardAt(height) }
            waitForIdle()
            val (_, bottom) = board.noteOnScreen()
            val barTop = window - board.chromeBottom.value
            assertTrue(bottom <= barTop, "at a $height px keyboard the note's foot ($bottom) is under the bar (top $barTop)")
        }
        val (top, _) = board.noteOnScreen()
        assertTrue(top >= 0f, "the note was pushed off the top: $top")
        // A pan, not a zoom.
        assertEquals(scale, board.controller.state.value.viewport.scale)

        runOnIdle { board.keyboardAt(KEYBOARD / 2) }
        waitForIdle()
        runOnIdle { board.keyboardAt(0) }
        waitForIdle()
        assertEquals(before, board.controller.state.value.viewport, "the camera did not go back where it was")
    }

    @Test
    fun withReducedMotionTheCameraMovesOnceTheKeyboardSettles() = runComposeUiTest {
        val board = showBoard(reducedMotion = true)
        val window = onRoot().fetchSemanticsNode().size.height.toFloat()
        parkNoteAtTheFoot(board, window)
        typeIntoTheNote()
        val before = board.controller.state.value.viewport

        mainClock.autoAdvance = false
        runOnIdle { board.keyboardAt(KEYBOARD) }
        mainClock.advanceTimeByFrame()
        assertEquals(before, board.controller.state.value.viewport, "the camera moved before the keyboard settled")
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        mainClock.autoAdvance = true
        waitForIdle()
        val (_, bottom) = board.noteOnScreen()
        assertTrue(bottom <= window - board.chromeBottom.value, "the note's foot ($bottom) is under the bar")
    }

    @Test
    fun withNothingBeingTypedTheKeyboardMovesNothing() = runComposeUiTest {
        val board = showBoard()
        val window = onRoot().fetchSemanticsNode().size.height.toFloat()
        parkNoteAtTheFoot(board, window)
        val before = board.controller.state.value.viewport
        runOnIdle { board.keyboardAt(KEYBOARD) }
        waitForIdle()
        assertEquals(before, board.controller.state.value.viewport)
    }

    private companion object {
        const val NOTE = "note-foot"
        const val NOTE_W = 240f
        const val NOTE_H = 120f
        const val KEYBOARD = 300
        const val LEFT = 24f
        const val PARKED_ABOVE_BAR = 8f
        const val SETTLE_MILLIS = 500L
        val BAR = 80.dp
        val REACH = 16.dp
    }
}
