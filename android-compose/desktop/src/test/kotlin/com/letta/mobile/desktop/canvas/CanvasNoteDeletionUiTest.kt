@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A note that has been typed in has to STAY deleted.
 *
 * Every note card composes its own editor, and that editor writes what it holds back to the
 * session on a timer. Removing the note does not un-write what is still pending, so a write that
 * lands after the removal re-creates the document - the note reappears a moment after it was
 * taken, which is what "they erase and the elements come back on release" describes.
 */
class CanvasNoteDeletionUiTest {

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Board", initialSceneJson = ""),
        )
    }

    /** Places a note, types in it, and returns its id once the typing has been persisted. */
    private fun androidx.compose.ui.test.ComposeUiTest.typedNote(session: CanvasSession): String {
        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        val noteId = session.documents().single().id
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithContentDescription("Note $noteId").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Note $noteId").performClick()
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("hello canvas")
        waitUntil(timeoutMillis = 5000) { session.documents().single().json.contains("hello canvas") }
        return noteId
    }

    /** True when nothing re-created a document within [millis] of it being removed. */
    private fun androidx.compose.ui.test.ComposeUiTest.staysEmpty(session: CanvasSession, millis: Long): Boolean =
        runCatching { waitUntil(timeoutMillis = millis) { session.documents().isNotEmpty() } }.isFailure

    @Test
    fun anErasedNoteDoesNotComeBack() = runComposeUiTest {
        val session = session()
        setContent { CanvasWorkspace(session = session) }
        val noteId = typedNote(session)

        onNodeWithContentDescription("Eraser").performClick()
        onNodeWithContentDescription("Note $noteId").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().isEmpty() }

        // Long enough to cover the editor's own persist tick, which is what brings it back.
        assertTrue(staysEmpty(session, 4000), "the erased note came back")
    }

    @Test
    fun aRemovedNoteDoesNotComeBack() = runComposeUiTest {
        val session = session()
        setContent { CanvasWorkspace(session = session) }
        val noteId = typedNote(session)

        onNodeWithContentDescription("Remove note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().isEmpty() }

        assertTrue(staysEmpty(session, 4000), "the removed note came back")
    }
}
