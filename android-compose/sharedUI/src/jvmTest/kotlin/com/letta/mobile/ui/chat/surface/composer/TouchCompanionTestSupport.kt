@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
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
import com.letta.mobile.ui.mascot.MascotTransportLayer
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

internal const val COMPANION_TEST_AGENT = "agent-1"
internal const val COMPANION_TEST_SETTLE_MILLIS = 1_000L
private const val PHONE_WIDTH = 412
private const val PHONE_HEIGHT = 900

internal val COMPANION_TEST_PROMPT = UiMessage(
    id = "u1",
    role = "user",
    content = "Sketch a kitchen layout.",
    timestamp = "2026-10-06T16:00:00Z",
)

/** A ready chat on the companion agent, typing or not, that the Touch companion tests drive. */
internal class CompanionTestPort(typing: Boolean = true) : ChatSessionPort {
    override val uiState = MutableStateFlow(
        ChatUiState(
            conversationState = ConversationState.Ready("conv-1"),
            messages = persistentListOf(COMPANION_TEST_PROMPT),
            isLoadingMessages = false,
            agentName = "Meridian",
            agentId = COMPANION_TEST_AGENT,
            isAgentTyping = typing,
        ),
    )
    override val composer = MutableStateFlow(ChatComposerUiState(canSend = true))
    override val actions: ChatActions = RecordingChatActions()
}

/** The Touch chat page on a phone-sized box under reduced motion, with the mascot layer around it. */
internal fun ComposeUiTest.setTouchCompanionContent(port: ChatSessionPort, shell: FakeMascotShell) {
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
}
