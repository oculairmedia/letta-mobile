@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.canvas.CanvasSamples
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class CanvasWorkspaceUiTest {

    @Test
    fun canvasWorkspace_rendersAndRespondsToButtonClicks() = runComposeUiTest {
        setContent {
            CanvasWorkspace(
                initialJson = CanvasSamples.buildCycleJson,
            )
        }

        // Verify initial action buttons exist
        onNodeWithText("Import Build Cycle").assertExists()
        onNodeWithText("Import Daily Loop").assertExists()
        onNodeWithText("Clear").assertExists()
        onNodeWithText("Export JSON").assertExists()
        onNodeWithText("Export SVG").assertExists()

        // Click Clear button and assert state updates
        onNodeWithText("Clear").performClick()
        onNodeWithText("Elements: 0 | Cleared canvas").assertExists()

        // Click Import Build Cycle button and assert elements load
        onNodeWithText("Import Build Cycle").performClick()
        onNodeWithText("Elements: 15 | Imported Build Cycle sample").assertExists()

        // Click Import Daily Loop button and assert elements load
        onNodeWithText("Import Daily Loop").performClick()
        onNodeWithText("Imported Daily Loop sample", substring = true).assertExists()
    }

    @Test
    fun canvasWorkspace_withSession_loadsInitialSceneAndSavesExport() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                title = "Session Diagram",
                initialSceneJson = CanvasSamples.buildCycleJson,
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

        // Click Export JSON
        onNodeWithText("Export JSON").performClick()
        onNodeWithText("Exported JSON", substring = true).assertExists()
    }

    @Test
    fun canvasWorkspace_withSession_autosavesDebouncedEdits() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                title = "Autosave Diagram",
                initialSceneJson = "",
            )
        }

        setContent {
            CanvasWorkspace(
                session = session,
            )
        }

        // Initially revision is 1 and sceneJson is empty
        kotlin.test.assertEquals(1L, session.document.value?.revision)

        // Click Import Build Cycle button to mutate canvas elements
        onNodeWithText("Import Build Cycle").performClick()
        onNodeWithText("Elements: 15", substring = true).assertExists()

        // Wait for 500ms debounce to fire and saveScene to complete
        waitUntil(timeoutMillis = 5000) {
            session.document.value?.revision == 2L
        }

        kotlin.test.assertEquals(2L, session.document.value?.revision)
        kotlin.test.assertTrue(session.sceneJsonOrEmpty().contains("\"elements\""))
    }

    @Test
    fun canvasWorkspace_agentReplaceScene_projectsDiagramIntoUiWithoutHumanDrawing() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = store,
                title = "Agent Diagram",
                initialSceneJson = "",
            )
        }

        val registry = com.letta.mobile.data.canvas.CanvasSessionRegistry()
        setContent {
            CanvasWorkspace(
                session = session,
                sessions = registry,
            )
        }

        // Initially empty
        onNodeWithText("Elements: 0", substring = true).assertExists()

        // Agent tool simulates replace_scene with Build Cycle fixture
        val replaceTool = com.letta.mobile.data.canvas.CanvasReplaceSceneTool(store, registry)
        kotlinx.coroutines.runBlocking {
            replaceTool.invoke(
                kotlinx.serialization.json.buildJsonObject {
                    put("canvas_id", session.canvasId.value)
                    put("scene_json", CanvasSamples.buildCycleJson)
                }
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
}