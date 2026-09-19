@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import io.ak1.drawbox.domain.usecase.UseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A checklist in a note has to be tickable on the board itself.
 *
 * The note card is the editor, not a preview, so the todo block's own checkbox is live - but the
 * card also runs board gestures over the whole of itself (making the note active, erasing it,
 * dragging it). Those must not swallow the tick: a checklist you can write and cannot tick is
 * indistinguishable, to the person using it, from a checklist that does not work at all.
 */
class CanvasNoteChecklistUiTest {

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Board", initialSceneJson = ""),
        )
    }

    private val isCheckbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    @Test
    fun aChecklistItemInANoteCanBeTickedAndUnticked() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }

        // Turn the note's first block into a checklist item through the board's own formatting bar.
        onNodeWithContentDescription("To-do").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5000) {
            onAllNodes(isCheckbox).fetchSemanticsNodes().isNotEmpty()
        }

        // Tick it: the block's checked state must reach the session, which is what a peer, the
        // agent and the next session all read.
        onAllNodes(isCheckbox).onFirst().performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().single().json.contains("\"checked\":true") }

        // And untick it again - a checklist that only fills up is half a checklist.
        onAllNodes(isCheckbox).onFirst().performClick()
        waitUntil(timeoutMillis = 5000) { !session.documents().single().json.contains("\"checked\":true") }
    }

    @Test
    fun aSecondNoteDoesNotLandOnTopOfTheFirstAndItsChecklistStaysTickable() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        onNodeWithContentDescription("To-do").performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodes(isCheckbox).fetchSemanticsNodes().isNotEmpty() }

        // A second note is added. Both are placed at the middle of the board, so without a cascade
        // this one lands exactly on top of the first and swallows every click meant for it.
        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 2 }
        waitForIdle()

        val frames = session.documents().mapNotNull { it.frame }
        assertEquals(2, frames.size)
        assertNotEquals(frames[0].x to frames[0].y, frames[1].x to frames[1].y)

        // And the checklist on the older note - no longer the active one - still ticks.
        onAllNodes(isCheckbox).onFirst().performClick()
        waitUntil(timeoutMillis = 5000) {
            session.documents().any { it.json.contains("\"checked\":true") }
        }
    }
}
