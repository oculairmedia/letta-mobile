@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.MascotTransportLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-bglj6.1: a selection held on a reply in the Touch page, with nothing changing,
 * keeps its Copy / Select all toolbar still. The selection manager re-shows (on Android:
 * re-invalidates, or hides while "moving") the toolbar whenever the selected text moves or
 * re-lays out, so a page that re-lays out its rows every frame makes the toolbar flicker.
 */
class ChatSelectionToolbarTest {
    private class Port : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = persistentListOf(
                    UiMessage(id = "u1", role = "user", content = "Sketch a kitchen layout.", timestamp = "2026-09-30T18:02:00Z"),
                    UiMessage(
                        id = "a1",
                        role = "assistant",
                        content = "Here's an L-shaped plan with an island and a pantry wall.",
                        timestamp = "2026-09-30T18:02:09Z",
                        runId = "run-1",
                    ),
                ),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = AGENT,
            ),
        )
        override val composer = MutableStateFlow(ChatComposerUiState(canSend = true))
        override val actions: ChatActions = RecordingChatActions()
    }

    /** Counts every toolbar update: each one is a re-show (or a hide) on the platform. */
    private class CountingToolbar : TextToolbar {
        var shows = 0
        var hides = 0
        override var status: TextToolbarStatus = TextToolbarStatus.Hidden

        override fun hide() {
            hides++
            status = TextToolbarStatus.Hidden
        }

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            shows++
            status = TextToolbarStatus.Shown
        }
    }

    @Test
    fun aHeldSelectionOnAStillTimelineKeepsItsToolbarStill() = runComposeUiTest {
        val toolbar = CountingToolbar()
        val shell = FakeMascotShell(AGENT)
        setContent {
            shell.Provide {
                MascotTransportLayer(reducedMotion = false) {
                    MaterialTheme {
                        CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                            Box(Modifier.size(PHONE_WIDTH.dp, PHONE_HEIGHT.dp)) {
                                ChatSurface(
                                    port = Port(),
                                    presentation = ChatSurfacePresentation.ChatFirst,
                                    onIntent = {},
                                    host = ChatSurfaceHost(openCanvas = {}),
                                    modifier = Modifier.fillMaxSize(),
                                    appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                                    platform = ChatSurfacePlatform(showKeyboardHints = false),
                                    canvas = { _ -> Box(Modifier.fillMaxSize()) },
                                )
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        onAllNodesWithTag(ChatRowTestTags.AGENT_TEXT)[0].performTouchInput { longClick(center) }
        waitForIdle()
        assertEquals(TextToolbarStatus.Shown, toolbar.status, "the long press selects and shows the toolbar")
        val shows = toolbar.shows
        val hides = toolbar.hides
        mainClock.autoAdvance = false
        repeat(HELD_FRAMES) { mainClock.advanceTimeByFrame() }
        assertEquals(shows, toolbar.shows, "the toolbar was re-shown while nothing moved")
        assertEquals(hides, toolbar.hides, "the toolbar was hidden while nothing moved")
    }

    private companion object {
        const val AGENT = "agent-1"
        const val PHONE_WIDTH = 412
        const val PHONE_HEIGHT = 900
        const val HELD_FRAMES = 60
    }
}
