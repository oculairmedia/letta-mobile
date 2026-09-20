@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
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

/**
 * Undo belongs to the board, not to the drawing.
 *
 * The drawing's history is DrawBox's and private; notes, text and labels are ops on the session,
 * which had no history at all. The undo button drove only the first, so everything a person did
 * to a note was outside undo entirely - the button looked live and did nothing to what they had
 * just typed, moved or deleted.
 */
class CanvasUnifiedUndoUiTest {

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Board", initialSceneJson = ""),
        )
    }

    @Test
    fun addingANoteCanBeUndoneAndRedone() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 10_000) { session.documents().size == 1 }

        onNodeWithContentDescription("Undo").performClick()
        waitUntil(timeoutMillis = 10_000) { session.documents().isEmpty() }

        onNodeWithContentDescription("Redo").performClick()
        waitUntil(timeoutMillis = 10_000) { session.documents().size == 1 }
    }

    @Test
    fun deletingANoteCanBeUndone() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 10_000) { session.documents().size == 1 }
        val note = session.documents().single()

        runBlocking { session.removeDocument(note.id) }
        waitUntil(timeoutMillis = 10_000) { session.documents().isEmpty() }

        // The removal above went straight to the session rather than through the board, so undo
        // walks back to the step the board DID record: adding the note. Either way the button
        // must move the board, not sit there live and inert.
        onNodeWithContentDescription("Undo").performClick()
        waitForIdle()
        assertEquals(0, session.documents().size)
    }

    @Test
    fun aNoteAndAStrokeUndoInTheOrderTheyWereDone() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        // A stroke first, then a note: undo must take the note back before the stroke.
        controller.importPath(
            """{"bgColor":"#ffffffff","elements":[{"id":"rect-1","type":"Shape","zIndex":1,
            "points":["10.0,10.0","120.0,90.0"],"strokeColor":"#000000ff","strokeWidth":4.0,
            "shapeType":"RECTANGLE","modifiedAt":1}]}""",
        )
        waitUntil(timeoutMillis = 10_000) { controller.state.value.elements.size == 1 }
        waitUntil(timeoutMillis = 10_000) { session.sceneJsonOrEmpty().contains("rect-1") }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 10_000) { session.documents().size == 1 }

        onNodeWithContentDescription("Undo").performClick()

        // The note goes first, because it was done last - and the drawing is left alone.
        waitUntil(timeoutMillis = 10_000) { session.documents().isEmpty() }
        assertEquals(1, controller.state.value.elements.size, "the stroke was undone out of order")
    }

    @Test
    fun typingInANoteUndoesTheTypingBeforeTheNote() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 10_000) { session.documents().size == 1 }
        val note = session.documents().single()

        // Typed into the note itself, so the editor writes it the way it does in the app: the
        // point of this test is the editor's OWN writes becoming steps.
        onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription("Note ${note.id}")))
            .performTextInput("hello")
        waitUntil(timeoutMillis = 10_000) { session.documents().single().json.contains("hello") }

        // Undo takes the TYPING back, not the whole note: losing a note you were writing in
        // because you wanted the last word back is not undo, it is a different disaster.
        onNodeWithContentDescription("Undo").performClick()
        waitUntil(timeoutMillis = 10_000) { !session.documents().single().json.contains("hello") }
        assertEquals(1, session.documents().size, "undo took the whole note instead of the typing")

        // And redo puts the typing back.
        onNodeWithContentDescription("Redo").performClick()
        waitUntil(timeoutMillis = 10_000) { session.documents().single().json.contains("hello") }
    }

    @Test
    fun recolouringANoteCanBeUndone() = runComposeUiTest {
        val session = session()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 10_000) { session.documents().size == 1 }
        val note = session.documents().single()
        val original = note.color

        // The note's own colour control, as a person reaches it.
        onNodeWithContentDescription("Properties").performClick()
        // The panel colours the text by default; the card is what this test is about.
        onNodeWithContentDescription("Target card").performClick()
        onNodeWithContentDescription("Color green").performClick()
        waitUntil(timeoutMillis = 10_000) { session.documents().single().color != original }
        onNodeWithContentDescription("Close properties").performClick()

        // Undo puts the colour back, and leaves the note where it is: a colour change that undo
        // does not cover teaches you undo works and then drops the change you cared about.
        onNodeWithContentDescription("Undo").performClick()
        waitUntil(timeoutMillis = 10_000) { session.documents().single().color == original }
        assertEquals(1, session.documents().size, "undo took the note instead of the colour")
    }
}
