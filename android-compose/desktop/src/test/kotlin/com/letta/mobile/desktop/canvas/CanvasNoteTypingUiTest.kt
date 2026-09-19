@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
 * The note you place on the board has to be typeable. The table block already proves the
 * canvas's own plumbing carries text input through the scaled board (see
 * [CanvasWorkspaceUiTest.canvasWorkspace_tableBlock_isInsertedEditedAndPersisted]); this
 * covers the editor's own paragraph, which is what a person meets first.
 */
class CanvasNoteTypingUiTest {

    private fun session(): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Typing Board", initialSceneJson = ""),
        )
    }

    @Test
    fun note_paragraph_acceptsTypedText() = runComposeUiTest {
        val session = session()
        setContent { CanvasWorkspace(session = session) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        val noteId = session.documents().single().id
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithContentDescription("Note $noteId").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithContentDescription("Note $noteId").performClick()
        val editable = onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertTrue(editable.isNotEmpty(), "the note exposes no editable node — the editor never became a text target")
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("hello canvas")

        waitUntil(timeoutMillis = 5000) { session.documents().single().json.contains("hello canvas") }
    }
}
