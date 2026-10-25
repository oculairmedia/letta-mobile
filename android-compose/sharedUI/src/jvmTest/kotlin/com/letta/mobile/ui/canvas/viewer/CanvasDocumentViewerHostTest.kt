@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.CanvasActorId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerId
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewers
import com.letta.mobile.ui.canvas.CANVAS_VIEWER_TOGGLE_TAG
import com.letta.mobile.ui.canvas.CanvasDocumentViewerHost
import com.letta.mobile.ui.canvas.canvasViewerOptionTag
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasDocumentViewerHostTest {
    @Test
    fun renderedToggleSwitchesViewsOverTheSameLiveReadOnlyDocument() = runComposeUiTest {
        val initialScene = """{"elements":[],"_future":{"retained":"untouched"}}"""
        val session = session(initialScene)
        val identity = session.canvasId
        val originalScene = session.document.value!!.sceneJson

        setContent {
            MaterialTheme {
                CanvasDocumentViewerHost(session, Modifier.fillMaxSize()) { canvasModifier ->
                    Text("Canvas board content", modifier = canvasModifier.testTag(CANVAS_BOARD_CONTENT_TAG))
                }
            }
        }

        onNodeWithTag(CANVAS_VIEWER_TOGGLE_TAG).assertIsDisplayed()
        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.CANVAS)).assertIsDisplayed()
        onNodeWithTag(CANVAS_BOARD_CONTENT_TAG).assertIsDisplayed()
        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.TEXT)).performClick()

        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertTextEquals(originalScene)
        onNodeWithTag(CANVAS_BOARD_CONTENT_TAG).assertDoesNotExist()
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(identity, session.canvasId)
        assertEquals(originalScene, session.document.value!!.sceneJson)

        runBlocking {
            session.setBackgroundPattern(CanvasBackgroundPattern(kind = CanvasBackgroundPattern.GRID))
        }
        assertTrue(session.document.value!!.sceneJson.contains("\"retained\":\"untouched\""))
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertTextEquals(session.document.value!!.sceneJson)

        runBlocking {
            session.applyAgentBatch(
                ops = listOf(
                    CanvasOp.SetBackgroundPatternOp(
                        opId = "agent-grid-update",
                        actorId = "agent-1",
                        lamport = 0,
                        pattern = CanvasBackgroundPattern(kind = CanvasBackgroundPattern.DOTS),
                    ),
                ),
                actorId = CanvasActorId("agent-1"),
            )
        }
        assertTrue(session.document.value!!.sceneJson.contains("\"retained\":\"untouched\""))
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertTextEquals(session.document.value!!.sceneJson)
        assertEquals("dots", session.backgroundPattern()?.kind)

        val updatedScene = session.document.value!!.sceneJson
        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.CANVAS)).performClick()
        onNodeWithTag(CANVAS_BOARD_CONTENT_TAG).assertIsDisplayed()
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertDoesNotExist()
        assertEquals(identity, session.canvasId)
        assertEquals(updatedScene, session.document.value!!.sceneJson)
    }

    @Test
    fun anUnknownViewerIdResolvesToTheBuiltInCanvasViewer() {
        val fallback = CanvasDocumentViewers.Core.viewerFor(CanvasDocumentViewerId("unknown.reader"))

        assertEquals(CanvasDocumentViewerId.CANVAS, fallback.id)
    }

    private fun session(scene: String): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(
                agentId = "agent-1",
                canvasId = CanvasId("canvas-viewer-test"),
                initialSceneJson = scene,
            ),
        )
    }

    private companion object {
        const val CANVAS_BOARD_CONTENT_TAG = "canvas-board-content"
    }
}
