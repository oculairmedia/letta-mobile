@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performMouseInput
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

    /**
     * Picking a note replaces the board's selection the way picking a shape does. It used to leave
     * the drawn selection standing, so a plain click on a note read as a shift-click and the note
     * joined the strokes already selected.
     */
    @Test
    fun pressingNote_replacesDrawnSelection() = runComposeUiTest {
        val session = session()
        val controller = io.ak1.drawbox.presentation.viewmodel.DrawBoxController(
            io.ak1.drawbox.presentation.reducer.Reducer(io.ak1.drawbox.domain.usecase.UseCase()),
        )
        setContent { CanvasWorkspace(session = session, controller = controller) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        val noteId = session.documents().single().id

        controller.importPath(
            """{"bgColor":"#ffffffff","elements":[{"id":"rect-1","type":"Shape","zIndex":1,
            "points":["10.0,10.0","120.0,90.0"],"strokeColor":"#000000ff","strokeWidth":4.0,
            "shapeType":"RECTANGLE","modifiedAt":1}]}""",
        )
        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.size == 1 }
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.SelectAt(androidx.compose.ui.geometry.Offset(60f, 10f), 12f))
        waitUntil(timeoutMillis = 5000) { controller.state.value.selectedIds == setOf("rect-1") }

        onNodeWithContentDescription("Note $noteId").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.selectedIds.isEmpty() }
    }

    /** The eraser takes a note the way it takes a stroke. */
    @Test
    fun eraser_removesNote() = runComposeUiTest {
        val session = session()
        setContent { CanvasWorkspace(session = session) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        val noteId = session.documents().single().id

        onNodeWithContentDescription("Eraser").performClick()
        onNodeWithContentDescription("Note $noteId").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().isEmpty() }
    }

    /**
     * The chrome's handles resize the note. They are the only way to resize one now — the note's
     * own corner dot is gone — so if this drag does nothing, the board has a control that looks
     * draggable and is not.
     */
    @Test
    fun chromeHandle_resizesNote() = runComposeUiTest {
        val session = session()
        setContent { CanvasWorkspace(session = session) }

        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        val noteId = session.documents().single().id
        onNodeWithContentDescription("Note $noteId").performClick()
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithContentDescription("Selection chrome").fetchSemanticsNodes().isNotEmpty()
        }
        val before = session.documents().single().frame!!

        // Grab the bottom-right handle, which sits half a handle in from the chrome box's corner.
        onNodeWithContentDescription("Selection chrome").performMouseInput {
            moveTo(Offset(width - 4f, height - 4f))
            press()
            moveTo(Offset(width + 20f, height + 14f))
            moveTo(Offset(width + 56f, height + 36f))
            release()
        }

        waitUntil(timeoutMillis = 5000) {
            val now = session.documents().single().frame!!
            now.width > before.width && now.height > before.height
        }
    }
}
