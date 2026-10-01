@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineTags
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test

/** The whole shared page's own layers (letta-mobile-bglj6.1). */
class ChatSurfaceUiTest {
    internal class TestPort(state: ChatUiState, composer: ChatComposerUiState = ChatComposerUiState(canSend = true)) :
        ChatSessionPort {
        val ui = MutableStateFlow(state)
        override val uiState: StateFlow<ChatUiState> = ui
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(composer)
        val recording = RecordingChatActions()
        override val actions: ChatActions = recording
    }

    private val ready = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        isLoadingMessages = false,
        messages = persistentListOf(
            UiMessage(id = "u1", role = "user", content = "Hello there", timestamp = "2026-09-30T18:02:00Z"),
        ),
    )

    private fun ComposeUiTest.show(
        port: TestPort,
        presentation: ChatSurfacePresentation,
        platform: ChatSurfacePlatform = ChatSurfacePlatform.Default,
        withCanvas: Boolean = true,
    ) {
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 480.dp, height = 720.dp)) {
                    ChatSurface(
                        port = port,
                        presentation = presentation,
                        onIntent = {},
                        host = ChatSurfaceHost(),
                        platform = platform,
                        canvas = if (withCanvas) ({ _ -> Text("CANVAS") }) else null,
                    )
                }
            }
        }
    }

    @Test
    fun timelineOverlayDrawsOverTheFullScreenTimeline() = runComposeUiTest {
        show(
            TestPort(ready),
            ChatSurfacePresentation.ChatFirst,
            platform = ChatSurfacePlatform(timelineOverlay = { Text("RINGS") }),
        )
        onNodeWithText("RINGS").assertExists()
        onNodeWithText("Hello there").assertExists()
    }

    @Test
    fun anErrorIsShownOnceThenClearedInFullScreen() = runComposeUiTest {
        val port = TestPort(ready.copy(error = "Send failed"))
        show(port, ChatSurfacePresentation.ChatFirst)
        outlastTheSnackbar()
        waitUntil(timeoutMillis = 10_000) { port.recording.clearedErrors == 1 }
    }

    @Test
    fun anErrorReachesTheUserWhileDocked() = runComposeUiTest {
        val port = TestPort(ready)
        show(port, ChatSurfacePresentation.CanvasFirst)
        runOnIdle { port.ui.value = ready.copy(error = "Send failed") }
        onNodeWithText("Send failed").assertExists()
        outlastTheSnackbar()
        waitUntil(timeoutMillis = 10_000) { port.recording.clearedErrors == 1 }
    }

    /**
     * A short snackbar stays about 4 s. Advance the test clock past it rather than waiting in real
     * time; the waits that follow then return at once.
     */
    private fun ComposeUiTest.outlastTheSnackbar() {
        mainClock.advanceTimeBy(SNACKBAR_SHORT_OUTLAST_MS)
    }

    private companion object {
        const val SNACKBAR_SHORT_OUTLAST_MS = 6_000L
    }

    @Test
    fun a2uiSurfacesShowAboveTheDock() = runComposeUiTest {
        val surface = A2uiSurfaceState(surfaceId = "surface-1", rootComponentId = null, components = emptyMap())
        show(TestPort(ready.copy(a2uiSurfaces = persistentMapOf("surface-1" to surface))), ChatSurfacePresentation.CanvasFirst)
        onNodeWithTag(ChatTimelineTags.A2UI_STACK).assertExists()
    }

    @Test
    fun scrollPositionSurvivesDockingAndExpanding() = runComposeUiTest {
        val messages = (0 until 120).map { i ->
            UiMessage(
                id = "m$i",
                role = if (i % 2 == 0) "user" else "assistant",
                content = "message $i",
                timestamp = "2026-09-30T%02d:%02d:00Z".format(i / 60, i % 60),
            )
        }
        val port = TestPort(ready.copy(messages = messages.toPersistentList()))
        var presentation by mutableStateOf(ChatSurfacePresentation.ChatFirst)
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 480.dp, height = 720.dp)) {
                    ChatSurface(port = port, presentation = presentation, onIntent = {}, host = ChatSurfaceHost(), canvas = { _ -> Text("CANVAS") })
                }
            }
        }
        onNodeWithTag(ChatTimelineTags.LIST).performScrollToIndex(60)
        onNodeWithTag(ChatTimelineTags.SCROLL_TO_LATEST).assertExists()

        runOnIdle { presentation = ChatSurfacePresentation.CanvasFirst }
        runOnIdle { presentation = ChatSurfacePresentation.ChatFirst }

        // Still reading where the user left off, not snapped back to the newest message.
        onNodeWithTag(ChatTimelineTags.SCROLL_TO_LATEST).assertExists()
    }

    @Test
    fun timelineOverlayIsNotDrawnWhileDocked() = runComposeUiTest {
        show(
            TestPort(ready),
            ChatSurfacePresentation.CanvasFirst,
            platform = ChatSurfacePlatform(timelineOverlay = { Text("RINGS") }),
        )
        onNodeWithText("RINGS").assertDoesNotExist()
    }
}
