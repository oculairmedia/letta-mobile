@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

        // Stroke colour from the rail.
        onNodeWithContentDescription("Stroke color").performClick()
        onNodeWithContentDescription("Color red").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.strokeColor == androidx.compose.ui.graphics.Color(0xFFE5484D) }

        // A closed-shape tool brings up fill and outline; picking a fill reaches the tool settings.
        onNodeWithContentDescription("Rectangle").performClick()
        onNodeWithContentDescription("Fill color").performClick()
        onNodeWithContentDescription("Color blue").performClick()
        waitUntil(timeoutMillis = 5000) { controller.state.value.currentItemFillColor == androidx.compose.ui.graphics.Color(0xFF3B82F6) }
        onNodeWithContentDescription("Outline on").performClick()
        waitUntil(timeoutMillis = 5000) { !controller.state.value.currentItemStrokeEnabled }

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

        // Recolouring from the card's swatch is written to the session.
        onNodeWithContentDescription("Note color").performClick()
        onNodeWithContentDescription("Color blue").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().single().color == "#bfdbfe" }
        onNodeWithText("125%").assertExists()

        // Opening the note large shows the full editor over the board, and closing it returns.
        onNodeWithContentDescription("Open note").performClick()
        onNodeWithContentDescription("Note editor").assertExists()
        onNodeWithContentDescription("Close note editor").performClick()
        onAllNodesWithContentDescription("Note editor").assertCountEquals(0)

        // The Text tool places a plain (transparent) block document; the active note's formatting
        // controls sit at the foot of the board, not inside the card.
        onNodeWithContentDescription("Text").performClick()
        waitUntil(timeoutMillis = 5000) { session.documents().size == 2 }
        val text = session.documents().first { it.id.startsWith("text-") }
        kotlin.test.assertEquals("#00000000", text.color)
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithContentDescription("Bold").fetchSemanticsNodes().isNotEmpty()
        }
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