@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.chat.projection.CanvasArtifactStatus
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-bglj6.13: "Show on canvas" on the shared page. With the page's own canvas it asks
 * that board's camera to frame the artifact and raises [ChatSurfaceIntent.OpenCanvas]; nothing
 * switches to the canvas until it is tapped.
 */
class ChatSurfaceShowOnCanvasTest {
    private val receipt = CanvasArtifactReceipt(
        artifactId = "weekend-plan",
        canvasId = "canvas-conversation-conv-1",
        revision = 42,
        status = CanvasArtifactStatus.Published,
        title = "Weekend plan",
        kinds = listOf(ComposeKind.NOTE),
        itemCount = 1,
        bounds = ComposeBounds(80f, 80f, 712f, 746f),
    )

    private val state = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        isLoadingMessages = false,
        messages = persistentListOf(
            UiMessage(id = "u1", role = "user", content = "Plan my weekend", timestamp = "2026-10-01T12:00:00Z"),
            UiMessage(
                id = "a1", role = "assistant", content = "It's on the board.", timestamp = "2026-10-01T12:00:05Z",
                runId = "run-1", artifacts = listOf(receipt),
            ),
        ),
    )

    @Test
    fun withThePagesOwnCanvasItFramesTheArtifactAndOpensTheCanvas() = runComposeUiTest {
        val intents = mutableListOf<ChatSurfaceIntent>()
        var canvasActions: ChatCanvasActions? = null
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 480.dp, height = 720.dp)) {
                    ChatSurface(
                        port = ChatSurfaceUiTest.TestPort(state),
                        presentation = ChatSurfacePresentation.ChatFirst,
                        onIntent = { intents += it },
                        host = ChatSurfaceHost(),
                        canvas = { actions ->
                            canvasActions = actions
                            Text("CANVAS")
                        },
                    )
                }
            }
        }
        // Composing the card switched nothing.
        assertEquals(emptyList(), intents)
        assertNull(canvasActions!!.camera.target)

        onNodeWithText("Show on canvas").performClick()

        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.OpenCanvas), intents)
        val target = canvasActions!!.camera.target!!
        assertEquals("canvas-conversation-conv-1", target.canvasId)
        assertEquals(Rect(80f, 80f, 792f, 826f), target.bounds)
    }

    @Test
    fun withoutACanvasTheHostsOwnShowOnCanvasIsCalled() = runComposeUiTest {
        val shown = mutableListOf<CanvasArtifactReceipt>()
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 480.dp, height = 720.dp)) {
                    ChatSurface(
                        port = ChatSurfaceUiTest.TestPort(state),
                        presentation = ChatSurfacePresentation.ChatFirst,
                        onIntent = {},
                        host = ChatSurfaceHost(openCanvas = {}, showOnCanvas = { shown += it }),
                        canvas = null,
                    )
                }
            }
        }
        onNodeWithText("Show on canvas").performClick()
        assertEquals(listOf(receipt), shown)
    }
}
