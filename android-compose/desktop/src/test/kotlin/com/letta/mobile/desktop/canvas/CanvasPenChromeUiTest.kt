@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasPenEvent
import com.letta.mobile.ui.canvas.CanvasPenRegistry
import com.letta.mobile.ui.canvas.LocalCanvasPenRegistry
import com.letta.mobile.ui.canvas.CanvasPenTool
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import io.ak1.drawbox.domain.usecase.UseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pen must not draw through the things you press and type in.
 *
 * Pen events are offered to the board by POSITION rather than by hit testing, so anything inside
 * the board's rectangle looked like drawing surface — including the note sitting on top of it.
 * The mouse never had the problem, because its events go through ordinary top-most-wins hit
 * testing and never reach the board at all.
 */
class CanvasPenChromeUiTest {

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Board", initialSceneJson = ""),
        )
    }

    /**
     * One quick stroke, as the pen reports it: down, a move, up.
     *
     * [at] is in the board's own pixels; the pen reports LOGICAL units, which the board scales by
     * the display density - so the test has to divide by the same density the board multiplies by,
     * or the stroke lands somewhere else entirely.
     */
    private fun stroke(
        at: Pair<Float, Float>,
        density: Float,
        target: com.letta.mobile.ui.canvas.CanvasPenTarget,
        registry: CanvasPenRegistry,
    ): Boolean {
        val x = at.first / density
        val y = at.second / density
        val step = 20f / density
        val taken = registry.deliver(target, CanvasPenEvent(CanvasPenEvent.Phase.DOWN, x, y, 0.6f, CanvasPenTool.DRAW))
        registry.deliver(target, CanvasPenEvent(CanvasPenEvent.Phase.MOVE, x + step, y + step, 0.6f, CanvasPenTool.DRAW))
        registry.deliver(target, CanvasPenEvent(CanvasPenEvent.Phase.UP, x + step, y + step, 0.6f, CanvasPenTool.DRAW))
        return taken
    }

    @Test
    fun theNoteIsWrittenInRatherThanDrawnOn() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        val registry = CanvasPenRegistry()
        var density = 1f
        var penTarget: com.letta.mobile.ui.canvas.CanvasPenTarget = com.letta.mobile.ui.canvas.DefaultPenTarget
        setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            penTarget = com.letta.mobile.ui.canvas.LocalCanvasPenTarget.current
            androidx.compose.runtime.CompositionLocalProvider(LocalCanvasPenRegistry provides registry) {
                CanvasWorkspace(session = session, controller = controller)
            }
        }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        controller.setMode(Mode.PEN)
        waitUntil(timeoutMillis = 5000) { controller.state.value.mode == Mode.PEN }
        waitUntil(timeoutMillis = 5000) { registry.hasConsumer(penTarget) }

        val frame = session.documents().single().frame!!
        val centreOfNote = controller.state.value.viewport.worldToScreen(
            androidx.compose.ui.geometry.Offset(frame.x + frame.width / 2f, frame.y + frame.height / 2f),
        )
        val elementsBefore = controller.state.value.elements.size

        val takenOverNote = stroke(centreOfNote.x to centreOfNote.y, density, penTarget, registry)
        waitForIdle()

        assertTrue(!takenOverNote, "the pen must decline a stroke that starts on a note")
        assertEquals(
            elementsBefore,
            controller.state.value.elements.size,
            "a stroke was drawn on the board underneath the note",
        )
    }

    @Test
    fun theOpenBoardStillDraws() = runComposeUiTest {
        // The other half of the same rule: excluding chrome must not cost the board its ink.
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        val registry = CanvasPenRegistry()
        var density = 1f
        var penTarget: com.letta.mobile.ui.canvas.CanvasPenTarget = com.letta.mobile.ui.canvas.DefaultPenTarget
        setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            penTarget = com.letta.mobile.ui.canvas.LocalCanvasPenTarget.current
            androidx.compose.runtime.CompositionLocalProvider(LocalCanvasPenRegistry provides registry) {
                CanvasWorkspace(session = session, controller = controller)
            }
        }

        controller.setMode(Mode.PEN)
        waitUntil(timeoutMillis = 5000) { registry.hasConsumer(penTarget) }
        val elementsBefore = controller.state.value.elements.size

        // Far from the rail down the left and clear of the bars top and bottom.
        stroke(600f to 400f, density, penTarget, registry)

        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.size > elementsBefore }
    }
}
