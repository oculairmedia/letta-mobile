@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import io.ak1.drawbox.domain.usecase.UseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Board-wide gestures have to reach notes that were added after the board was composed.
 *
 * The handlers for these live in long-lived lambdas (the intent collector, the board's pointer
 * input), which keep the `documents` list they were created with. That is why the eraser and the
 * marquee worked on every drawn shape and on no note: shapes are read fresh from controller.state,
 * notes came from a list captured before they existed.
 */
class CanvasNoteBoardIntentsUiTest {

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Board", initialSceneJson = ""),
        )
    }

    @Test
    fun eraserDragTakesANoteAddedAfterComposition() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        val frame = session.documents().single().frame!!

        // Drag the eraser across the board and over the note, the way a hand does.
        onNodeWithContentDescription("Eraser").performClick()
        val centre = controller.state.value.viewport.worldToScreen(
            Offset(frame.x + frame.width / 2f, frame.y + frame.height / 2f),
        )
        onNodeWithContentDescription("Canvas board").performMouseInput {
            moveTo(Offset(centre.x - 120f, centre.y - 120f))
            press()
            moveTo(Offset(centre.x - 40f, centre.y - 40f))
            moveTo(centre)
            release()
        }

        waitUntil(timeoutMillis = 5000) { session.documents().isEmpty() }
    }

    @Test
    fun marqueeTakesInANoteAddedAfterComposition() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        val frame = session.documents().single().frame!!

        controller.onIntent(
            Intent.CommitMarquee(
                Rect(frame.x - 50f, frame.y - 50f, frame.x + frame.width + 50f, frame.y + frame.height + 50f),
            ),
        )
        waitForIdle()

        // A note inside the marquee is selected, so it draws the selection chrome.
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithContentDescription("Selection chrome").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
