@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.MascotTransport
import com.letta.mobile.ui.mascot.MascotTransportLayer
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-bglj6.1.9: the Touch page shows its companion above the bar only while the agent
 * works. Hidden (at once, under reduced motion), the seat collapses to nothing rather than going
 * unplaced: an unplaced seat left its last, whole bounds published, so the mascot layer went on
 * drawing the character over the bar and taking its taps.
 */
class TouchCompanionHiddenSeatTest {
    private class Port : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = persistentListOf(
                    UiMessage(id = "u1", role = "user", content = "Sketch a kitchen layout.", timestamp = "2026-09-30T18:02:00Z"),
                ),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = AGENT,
                isAgentTyping = true,
            ),
        )
        override val composer = MutableStateFlow(ChatComposerUiState(canSend = true))
        override val actions: ChatActions = RecordingChatActions()
    }

    @Test
    fun aHiddenCompanionPublishesNoBoundsUnderReducedMotion() = runComposeUiTest {
        val port = Port()
        val shell = FakeMascotShell(AGENT)
        // The thinking row and the mascot layer run without end: step the clock by hand.
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    MascotTransportLayer(reducedMotion = true) {
                        MaterialTheme {
                            Box(Modifier.size(PHONE_WIDTH.dp, PHONE_HEIGHT.dp)) {
                                ChatSurface(
                                    port = port,
                                    presentation = ChatSurfacePresentation.ChatFirst,
                                    onIntent = {},
                                    host = ChatSurfaceHost(),
                                    modifier = Modifier.fillMaxSize(),
                                    appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                                    platform = ChatSurfacePlatform(showKeyboardHints = false),
                                )
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        val key = MascotTransport.SeatKey(AGENT, MascotStage.COMPOSER_COMPANION)
        val working = shell.transport.seat(key)?.bounds
        assertTrue(working != null && working.width > 0f, "while the agent works the companion stands above the bar: $working")
        // The run ends: the companion goes at once.
        port.uiState.value = port.uiState.value.copy(isAgentTyping = false)
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        val hidden = shell.transport.seat(key)?.bounds
        assertTrue(hidden == null || hidden.width <= 0f || hidden.height <= 0f, "the hidden companion still has bounds: $hidden")
    }

    private companion object {
        const val AGENT = "agent-1"
        const val PHONE_WIDTH = 412
        const val PHONE_HEIGHT = 900
        const val SETTLE_MILLIS = 1_000L
    }
}
