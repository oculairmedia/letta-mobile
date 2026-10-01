@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.chat.surface.composer.RecordingChatActions
import com.letta.mobile.ui.chat.surface.sendflight.SendFlightTestTags
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import com.letta.mobile.ui.theme.ChatMotionTokens
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-cc25e: the send flight wired through the real page. Pressing send on the
 * composer flies the draft into the prompt row the owner adds, full screen and docked.
 */
class SendFlightChatSurfaceTest {
    /** An owner that, like the real ones, appends the prompt (oldest first) and clears the draft on send. */
    private class SendingPort : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = persistentListOf(
                    UiMessage(id = "u0", role = "user", content = "Hi", timestamp = "2026-09-30T17:59:00Z"),
                    UiMessage(id = "a0", role = "assistant", content = "Hello!", timestamp = "2026-09-30T18:00:00Z"),
                ),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = "agent-1",
            ),
        )
        override val composer = MutableStateFlow(ChatComposerUiState(text = SENT, canSend = true))
        val recorded = RecordingChatActions()
        override val actions: ChatActions = object : ChatActions by recorded {
            override fun send() {
                recorded.send()
                val prompt = UiMessage(id = "u1", role = "user", content = composer.value.text, timestamp = "2026-09-30T18:01:00Z")
                uiState.value = uiState.value.copy(messages = (uiState.value.messages + prompt).toPersistentList())
                composer.value = composer.value.copy(text = "")
            }
        }
    }

    private fun flight(presentation: ChatSurfacePresentation) = runComposeUiTest {
        val port = SendingPort()
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = port,
                    presentation = presentation,
                    onIntent = {},
                    host = ChatSurfaceHost(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        frames(SETTLE_FRAMES)

        onNodeWithTag(ComposerTestTags.SEND).performClick()
        frames(2)
        assertEquals(1, port.recorded.count("send"), "the send went straight through")
        assertEquals(1, ghosts(), "the ghost appears at send")

        mainClock.advanceTimeBy(ChatMotionTokens.SendFlight.FLIGHT_MILLIS + FRAME_SLACK)
        val row = bounds(hasTestTag(ChatRowTestTags.USER_PROMPT) and hasText(SENT, substring = true))
        val ghost = bounds(hasTestTag(SendFlightTestTags.GHOST))
        assertTrue(abs(row.top - ghost.top) <= PIXEL_TOLERANCE, "the ghost lands on the new row ($ghost vs $row)")

        mainClock.advanceTimeBy(ChatMotionTokens.SendFlight.HANDOFF_MILLIS + FRAME_SLACK)
        assertEquals(0, ghosts(), "the ghost hands off to the row")
    }

    private fun ComposeUiTest.frames(count: Int) = repeat(count) { mainClock.advanceTimeByFrame() }

    private fun ComposeUiTest.ghosts(): Int = onAllNodes(hasTestTag(SendFlightTestTags.GHOST)).fetchSemanticsNodes().size

    private fun ComposeUiTest.bounds(matcher: SemanticsMatcher): Rect =
        onAllNodes(matcher).fetchSemanticsNodes().single().boundsInRoot

    @Test
    fun fullScreenSendFliesIntoTheTimeline() = flight(ChatSurfacePresentation.ChatFirst)

    @Test
    fun dockedSendFliesIntoTheReplyCard() = flight(ChatSurfacePresentation.CanvasFirst)

    private companion object {
        const val SENT = "Plan a taco night for six"
        const val SETTLE_FRAMES = 10
        const val FRAME_SLACK = 64L
        const val PIXEL_TOLERANCE = 1.5f
    }
}
