@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performClick
import io.ak1.drawbox.domain.model.bounds
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.canvas.CanvasSamples
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class CanvasWorkspaceUiTest {

    private fun androidx.compose.ui.test.ComposeUiTest.openMenuItem(label: String) {
        onNodeWithContentDescription("More").performClick()
        onNodeWithText(label).performClick()
    }

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
    fun canvasWorkspace_addNote_placesABlockDocumentOnTheBoard() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(title = "Notes Board", initialSceneJson = ""),
            )
        }

        setContent {
            CanvasWorkspace(session = session)
        }

        onAllNodesWithContentDescription("Note ", substring = true).assertCountEquals(0)
        // Zoom in first: placing and moving notes writes the session, and that must never reload
        // the drawing and throw the camera back to 100%.
        onNodeWithContentDescription("Zoom in").performClick()
        onNodeWithText("125%").assertExists()
        onNodeWithContentDescription("Add note").performClick()

        // The note is written to the session with a frame and shows up on the board as a card.
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        onNodeWithText("125%").assertExists()
        onAllNodesWithText("Agent updated canvas", substring = true).assertCountEquals(0)
        val note = session.documents().single()
        kotlin.test.assertNotNull(note.frame, "a placed note carries its board frame")
        kotlin.test.assertEquals("#fde68a", note.color, "a new note starts yellow")
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithContentDescription("Note ${note.id}").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Remove note").assertExists()

        // Recolouring the active note through the master control is written to the session.
        onNodeWithContentDescription("Properties").performClick()
        onNodeWithContentDescription("Target card").performClick()
        onNodeWithContentDescription("Color blue").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().single().color == "#bfdbfe" }
        onNodeWithContentDescription("Close properties").performClick()
        onNodeWithText("125%").assertExists()

        // Opening the note large shows the full editor over the board, and closing it returns.
        onNodeWithContentDescription("Open note").performClick()
        onNodeWithContentDescription("Note editor").assertExists()
        onNodeWithContentDescription("Close note editor").performClick()
        onAllNodesWithContentDescription("Note editor").assertCountEquals(0)

        // The Text tool places a plain (transparent) block document; the active note's formatting
        // controls sit at the foot of the board, not inside the card, with every block kind.
        onNodeWithContentDescription("Text").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 2 }
        val text = session.documents().first { it.id.startsWith("text-") }
        kotlin.test.assertEquals("#00000000", text.color)
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithContentDescription("Bold").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("To-do").performClick()
        waitUntil(timeoutMillis = 5000) {
            session.documents().first { it.id == text.id }.json.contains("\"todo\"")
        }

        // A text element has no note chrome, and its bar sets size, family, alignment and colour,
        // all of which persist with the document.
        // The one "Move note" grip on the board belongs to the sticky note placed above.
        onAllNodesWithContentDescription("Move note").assertCountEquals(1)
        onNodeWithContentDescription("Move text").assertExists()
        onNodeWithContentDescription("Properties").performClick()
        onAllNodesWithContentDescription("Target card").assertCountEquals(0)
        onNodeWithContentDescription("Size L").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().first { it.id == text.id }.style?.fontScale == 1.4f }
        onNodeWithContentDescription("Font Serif").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().first { it.id == text.id }.style?.fontFamily == "serif" }
        onNodeWithContentDescription("Align center").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().first { it.id == text.id }.style?.align == "center" }
        onNodeWithContentDescription("Color blue").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().first { it.id == text.id }.style?.textColor == "#3b82f6" }
        val styled = session.documents().first { it.id == text.id }.style!!
        kotlin.test.assertEquals(1.4f, styled.fontScale, "colour must not reset the size")
        onNodeWithContentDescription("Close properties").performClick()

        // The active note's bar deletes it.
        onNodeWithContentDescription("Delete note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().none { it.id == text.id } }
        onAllNodesWithContentDescription("Delete note").assertCountEquals(0)
    }

    @Test
    fun canvasWorkspace_backgroundPattern_persistsToSessionAndReachesTheBoard() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(title = "Pattern Board", initialSceneJson = ""),
            )
        }
        val controller = io.ak1.drawbox.presentation.viewmodel.DrawBoxController(
            io.ak1.drawbox.presentation.reducer.Reducer(io.ak1.drawbox.domain.usecase.UseCase()),
        )
        setContent {
            CanvasWorkspace(controller = controller, session = session)
        }
        kotlin.test.assertNull(session.backgroundPattern())

        onNodeWithContentDescription("More").performClick()
        onNodeWithContentDescription("Pattern dots").performClick()
        waitUntil(timeoutMillis = 5000) { session.backgroundPattern()?.kind == com.letta.mobile.data.canvas.CanvasBackgroundPattern.DOTS }
        waitUntil(timeoutMillis = 5000) { controller.state.value.bgPattern?.painter != null }
        onNodeWithContentDescription("Spacing 64").performClick()
        waitUntil(timeoutMillis = 5000) { session.backgroundPattern()?.spacing == 64f }
        onNodeWithContentDescription("Pattern color").performClick()
        onNodeWithContentDescription("Color blue").performClick()
        waitUntil(timeoutMillis = 5000) { session.backgroundPattern()?.colorHex == "#3b82f6" }
        kotlin.test.assertEquals(
            com.letta.mobile.data.canvas.CanvasBackgroundPattern(com.letta.mobile.data.canvas.CanvasBackgroundPattern.DOTS, 64f, "#3b82f6"),
            session.backgroundPattern(),
        )
        // The pattern is scene-root state, so the drawing was not re-imported (camera untouched).
        onAllNodesWithText("Agent updated canvas", substring = true).assertCountEquals(0)
    }

    @Test
    fun canvasWorkspace_arrowSnapsToANoteAndFollowsItWhenItMoves() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(title = "Snap Board", initialSceneJson = ""),
            )
        }
        val controller = io.ak1.drawbox.presentation.viewmodel.DrawBoxController(
            io.ak1.drawbox.presentation.reducer.Reducer(io.ak1.drawbox.domain.usecase.UseCase()),
        )
        // A note at world (400,100) 200x120: its left anchor is (400,160).
        val frame = com.letta.mobile.data.canvas.CanvasDocumentFrame(x = 400f, y = 100f, width = 200f, height = 120f)
        kotlinx.coroutines.runBlocking { session.setDocument("note-a", "", frame = frame, color = "#fde68a") }
        setContent {
            CanvasWorkspace(controller = controller, session = session)
        }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithContentDescription("Note note-a").fetchSemanticsNodes().isNotEmpty() }

        // Draw an arrow from open board towards the note's left edge; the end lands within the
        // snap radius of the left anchor and is snapped onto it and bound to the note.
        onNodeWithContentDescription("Arrow").performClick()
        val board = onNodeWithContentDescription("Canvas board")
        board.performMouseInput {
            moveTo(androidx.compose.ui.geometry.Offset(200f, 300f))
            press()
            moveTo(androidx.compose.ui.geometry.Offset(300f, 220f))
            moveTo(androidx.compose.ui.geometry.Offset(392f, 166f))
            release()
        }
        waitUntil(timeoutMillis = 5000) {
            val arrow = controller.state.value.elements.filterIsInstance<io.ak1.drawbox.domain.model.Element.Shape>().firstOrNull()
            arrow != null && arrow.points.last() == androidx.compose.ui.geometry.Offset(400f, 160f)
        }
        val arrowId = controller.state.value.elements.single().id
        waitUntil(timeoutMillis = 5000) { session.arrowBindings()[arrowId]?.end?.documentId == "note-a" }
        kotlin.test.assertEquals("left", session.arrowBindings()[arrowId]?.end?.side)

        // Moving the note (as a drag commit or a peer would) re-points the bound end.
        kotlinx.coroutines.runBlocking { session.moveDocument("note-a", frame.copy(x = 500f, y = 300f)) }
        waitUntil(timeoutMillis = 5000) {
            val arrow = controller.state.value.elements.filterIsInstance<io.ak1.drawbox.domain.model.Element.Shape>().single()
            arrow.points.last() == androidx.compose.ui.geometry.Offset(500f, 360f)
        }
    }

    @Test
    fun canvasWorkspace_keysAndDuplicate_actOnTheSelectionOrTheActiveNote() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(title = "Keys Board", initialSceneJson = ""),
            )
        }
        val controller = io.ak1.drawbox.presentation.viewmodel.DrawBoxController(
            io.ak1.drawbox.presentation.reducer.Reducer(io.ak1.drawbox.domain.usecase.UseCase()),
        )
        setContent { CanvasWorkspace(controller = controller, session = session) }

        // A note: Duplicate from its bar makes a second document with the same colour, offset.
        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        val first = session.documents().single()
        onNodeWithContentDescription("Duplicate note").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 2 }
        val copy = session.documents().first { it.id != first.id }
        kotlin.test.assertEquals(first.color, copy.color)
        kotlin.test.assertEquals(first.frame!!.x + 20f, copy.frame!!.x)
        // The copy is the active note; Delete on the board removes it (its editor is not focused).
        onNodeWithContentDescription("Canvas workspace").requestFocus()
        onNodeWithContentDescription("Canvas workspace").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Delete) }
        waitUntil(timeoutMillis = 5000) { session.documents().size == 1 }
        kotlin.test.assertEquals(first.id, session.documents().single().id)

        // A drawn rectangle: select it, Ctrl+D duplicates it, Delete removes the (new) selection,
        // Ctrl+Z brings it back, Esc clears the selection.
        controller.importPath(
            """{"bgColor":"#ffffffff","elements":[{"id":"rect-1","type":"Shape","zIndex":1,
            "points":["10.0,10.0","120.0,90.0"],"strokeColor":"#000000ff","strokeWidth":4.0,
            "shapeType":"RECTANGLE","modifiedAt":1}]}""",
        )
        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.size == 1 }
        onNodeWithContentDescription("Select").performClick()
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.SelectAt(androidx.compose.ui.geometry.Offset(60f, 10f), 12f))
        waitUntil(timeoutMillis = 5000) { controller.state.value.selectedIds == setOf("rect-1") }
        onNodeWithContentDescription("Canvas workspace").requestFocus()
        onNodeWithContentDescription("Canvas workspace").performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.CtrlLeft); pressKey(androidx.compose.ui.input.key.Key.D); keyUp(androidx.compose.ui.input.key.Key.CtrlLeft)
        }
        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.size == 2 }
        val copyShape = controller.state.value.elements.filterIsInstance<io.ak1.drawbox.domain.model.Element.Shape>().first { it.id != "rect-1" }
        kotlin.test.assertEquals(androidx.compose.ui.geometry.Offset(30f, 30f), copyShape.points.first())
        waitUntil(timeoutMillis = 5000) { controller.state.value.selectedIds == setOf(copyShape.id) }
        onNodeWithContentDescription("Canvas workspace").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Delete) }
        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.size == 1 }
        onNodeWithContentDescription("Canvas workspace").performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.CtrlLeft); pressKey(androidx.compose.ui.input.key.Key.Z); keyUp(androidx.compose.ui.input.key.Key.CtrlLeft)
        }
        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.size == 2 }
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.SelectAt(androidx.compose.ui.geometry.Offset(60f, 10f), 12f))
        waitUntil(timeoutMillis = 5000) { controller.state.value.selectedIds.isNotEmpty() }
        onNodeWithContentDescription("Canvas workspace").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        waitUntil(timeoutMillis = 5000) { controller.state.value.selectedIds.isEmpty() }
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
    fun canvasWorkspace_marqueeTakesInNotes_movesThemTogether_andDeleteRemovesAll() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(title = "Group Board", initialSceneJson = ""),
            )
        }
        val controller = io.ak1.drawbox.presentation.viewmodel.DrawBoxController(
            io.ak1.drawbox.presentation.reducer.Reducer(io.ak1.drawbox.domain.usecase.UseCase()),
        )
        val a = com.letta.mobile.data.canvas.CanvasDocumentFrame(100f, 100f, 200f, 100f)
        val b = com.letta.mobile.data.canvas.CanvasDocumentFrame(400f, 100f, 200f, 100f)
        val far = com.letta.mobile.data.canvas.CanvasDocumentFrame(2000f, 2000f, 200f, 100f)
        kotlinx.coroutines.runBlocking {
            session.setDocument("note-a", "", frame = a, color = "#fde68a")
            session.setDocument("note-b", "", frame = b, color = "#fde68a")
            session.setDocument("note-far", "", frame = far, color = "#fde68a")
        }
        setContent { CanvasWorkspace(controller = controller, session = session) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithContentDescription("Note note-b").fetchSemanticsNodes().isNotEmpty() }

        // A marquee over the first two notes selects them (the selection bar appears), not the far one.
        onNodeWithContentDescription("Select").performClick()
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.CommitMarquee(androidx.compose.ui.geometry.Rect(50f, 50f, 700f, 300f)))
        waitUntil(timeoutMillis = 5000) { onAllNodesWithContentDescription("Delete selection").fetchSemanticsNodes().isNotEmpty() }

        // Moving the selection (as a drag of it does) moves both notes on release, in one revision.
        val before = session.document.value!!.revision
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.MoveSelected(androidx.compose.ui.geometry.Offset(30f, 40f)))
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.EndTransform)
        waitUntil(timeoutMillis = 5000) { session.documents().first { it.id == "note-a" }.frame?.x == 130f }
        val frames = session.documents().associate { it.id to it.frame!! }
        kotlin.test.assertEquals(com.letta.mobile.data.canvas.CanvasDocumentFrame(130f, 140f, 200f, 100f), frames["note-a"])
        kotlin.test.assertEquals(com.letta.mobile.data.canvas.CanvasDocumentFrame(430f, 140f, 200f, 100f), frames["note-b"])
        kotlin.test.assertEquals(far, frames["note-far"])
        kotlin.test.assertEquals(before + 1, session.document.value!!.revision)

        // Delete removes every selected note and leaves the far one.
        onNodeWithContentDescription("Delete selection").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().map { it.id } == listOf("note-far") }
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

    @Test
    fun canvasWorkspace_withSession_loadsInitialSceneAndSavesExport() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(
                    title = "Session Diagram",
                    initialSceneJson = CanvasSamples.buildCycleJson,
                ),
            )
        }

        setContent {
            CanvasWorkspace(
                session = session,
            )
        }

        // Verify session diagram loaded
        onNodeWithText("Session Diagram", substring = true).assertExists()
        onNodeWithText("Elements: 15", substring = true).assertExists()

        // Export JSON
        openMenuItem("Export JSON")
        onNodeWithText("Exported JSON", substring = true).assertExists()
    }

    @Test
    fun canvasWorkspace_withSession_autosavesDebouncedEdits() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(
                    title = "Autosave Diagram",
                    initialSceneJson = "",
                ),
            )
        }

        setContent {
            CanvasWorkspace(
                session = session,
            )
        }

        // Initially revision is 1 and sceneJson is empty
        kotlin.test.assertEquals(1L, session.document.value?.revision)

        // Import Build Cycle to mutate canvas elements
        openMenuItem("Import Build Cycle")
        onNodeWithText("Elements: 15", substring = true).assertExists()

        // Wait for 500ms debounce to fire and saveScene to complete
        waitUntil(timeoutMillis = 5000) {
            session.document.value?.revision == 2L
        }
        // The autosave round trip must not be mistaken for an external change and re-imported.
        onAllNodesWithText("Agent updated canvas", substring = true).assertCountEquals(0)

        kotlin.test.assertEquals(2L, session.document.value?.revision)
        kotlin.test.assertTrue(session.sceneJsonOrEmpty().contains("\"elements\""))
    }

    @Test
    fun canvasWorkspace_agentReplaceScene_projectsDiagramIntoUiWithoutHumanDrawing() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(
                    title = "Agent Diagram",
                    agentId = "agent-1",
                    initialSceneJson = "",
                ),
            )
        }
        // The workspace and the tool must share one registry, or the tool only ever sees the
        // store and the open session never hears about the agent's replace.
        val sessions = com.letta.mobile.data.canvas.CanvasSessionRegistry()

        setContent {
            CanvasWorkspace(
                session = session,
                sessionRegistry = sessions,
            )
        }

        // Initially empty
        onNodeWithText("Elements: 0", substring = true).assertExists()

        // Agent tool simulates replace_scene with Build Cycle fixture
        val replaceTool = com.letta.mobile.data.canvas.CanvasReplaceSceneTool(store, sessions)
        kotlinx.coroutines.runBlocking {
            replaceTool.invoke(
                kotlinx.serialization.json.buildJsonObject {
                    put("canvas_id", session.canvasId.value)
                    put("scene_json", CanvasSamples.buildCycleJson)
                },
                agentId = "agent-1",
            )
        }

        // Wait for UI to observe revision bump and project diagram into DrawBoxController
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithText("Elements: 15", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Verify diagram is visible without human drawing (15 elements from Build Cycle fixture)
        onNodeWithText("Agent updated canvas (rev 2)", substring = true).assertExists()
        onNodeWithText("Elements: 15", substring = true).assertExists()
    }

    @Test
    fun canvasWorkspace_withPresence_rendersPeerCursorAndRemovesOnLeave() = runComposeUiTest {
        val presenceTransport = com.letta.mobile.data.canvas.InMemoryCanvasPresenceTransport()
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val canvasId = com.letta.mobile.data.canvas.CanvasId("presence-test-canvas")
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(
                    canvasId = canvasId,
                    title = "Presence Canvas",
                ),
            )
        }

        setContent {
            CanvasWorkspace(
                session = session,
                presenceTransport = presenceTransport,
                currentPeerId = "local-user",
            )
        }

        // Initially no peer cursor
        onAllNodesWithText("Alice (Peer)").assertCountEquals(0)

        // Peer arrives and updates cursor position
        kotlinx.coroutines.runBlocking {
            presenceTransport.updatePresence(
                canvasId = canvasId,
                presence = com.letta.mobile.data.canvas.CanvasPresence(
                    peerId = "peer-alice",
                    displayName = "Alice (Peer)",
                    colorHex = "#e5484dff",
                    cursorX = 120f,
                    cursorY = 180f,
                    isActive = true,
                )
            )
        }

        // Second client cursor visible!
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithText("Alice (Peer)").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Alice (Peer)").assertExists()

        // Peer leaves (isActive = false)
        kotlinx.coroutines.runBlocking {
            presenceTransport.updatePresence(
                canvasId = canvasId,
                presence = com.letta.mobile.data.canvas.CanvasPresence(
                    peerId = "peer-alice",
                    displayName = "Alice (Peer)",
                    colorHex = "#e5484dff",
                    cursorX = 120f,
                    cursorY = 180f,
                    isActive = false,
                )
            )
        }

        // Leave removes presence!
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithText("Alice (Peer)").fetchSemanticsNodes().isEmpty()
        }
        onAllNodesWithText("Alice (Peer)").assertCountEquals(0)
    }

    @Test
    fun canvasWorkspace_twoSessionsWithSharedHostTransport_converge() = runComposeUiTest {
        val sharedTransport = DesktopCanvasHostSync.syncTransport
        val storeA = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val storeB = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val canvasId = com.letta.mobile.data.canvas.CanvasId("host-sync-canvas")

        val sessionA = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = storeA,
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(
                    canvasId = canvasId,
                    title = "Host Sync A",
                    syncTransport = sharedTransport,
                    // Owner-only by default; both hosts edit this canvas, so both are named.
                    acl = com.letta.mobile.data.canvas.CanvasAcl(ownerUserId = "host-a", writerUserIds = setOf("host-b")),
                ),
            )
        }
        val sessionB = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = storeB,
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(
                    canvasId = canvasId,
                    title = "Host Sync B",
                    syncTransport = sharedTransport,
                    // Owner-only by default; both hosts edit this canvas, so both are named.
                    acl = com.letta.mobile.data.canvas.CanvasAcl(ownerUserId = "host-a", writerUserIds = setOf("host-b")),
                ),
            )
        }

        // Host UI binds sessionA (startSync is invoked internally in LaunchedEffect)
        setContent {
            CanvasWorkspace(
                session = sessionA,
                presenceTransport = DesktopCanvasHostSync.presenceTransport,
                currentPeerId = "host-a",
            )
        }

        // Initially 0 elements
        onNodeWithText("Elements: 0", substring = true).assertExists()

        val peerElementJson = """
            {
                "id": "card-b",
                "type": "Text",
                "zIndex": 10,
                "points": [],
                "strokeColor": "#1b2a41ff",
                "strokeWidth": 0.0,
                "modifiedAt": 1782669085173,
                "text": "Card From Peer",
                "fontFamilyKey": "serif",
                "fontSize": 64.0,
                "alignment": "CENTER",
                "textTopLeft": "502.0,212.0",
                "wrapWidth": 900.0
            }
        """.trimIndent()

        // Session B applies a remote op over shared host transport
        kotlinx.coroutines.runBlocking {
            sessionB.applyLocal(
                com.letta.mobile.data.canvas.CanvasOp.AddElementOp(
                    opId = "op-shared-1",
                    actorId = "host-b",
                    lamport = 1L,
                    elementId = "card-b",
                    elementJson = peerElementJson,
                )
            )
        }

        // Session A observes and projects into DrawBox controller
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithText("Elements: 1", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Elements: 1", substring = true).assertExists()
    }
}