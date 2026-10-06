@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
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
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineTags
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.MascotTransportLayer
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-bglj6.1.21: on the phone, elapsed time and the running tool sit beside the
 * companion mascot. The timeline thinking row stays off whenever a mascot is present.
 */
class TouchCompanionStatusUiTest {
    private class Port(typing: Boolean = true) : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = persistentListOf(PROMPT),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = AGENT,
                isAgentTyping = typing,
            ),
        )
        override val composer = MutableStateFlow(ChatComposerUiState(canSend = true))
        override val actions: ChatActions = RecordingChatActions()
    }

    @Test
    fun theCompanionShowsElapsedThinkingAndHidesTheTimelineRow() = runComposeUiTest {
        val port = Port()
        val shell = FakeMascotShell(AGENT)
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
        onNodeWithTag(ComposerTestTags.TOUCH_COMPANION_STATUS).assertExists()
        onNodeWithText("Thinking…", substring = true).assertExists()
        onNodeWithTag(ChatTimelineTags.THINKING).assertDoesNotExist()
    }

    @Test
    fun aRunningToolIsNamedBesideTheCompanion() = runComposeUiTest {
        val port = Port()
        port.uiState.value = port.uiState.value.copy(
            isStreaming = true,
            messages = persistentListOf(
                PROMPT,
                UiMessage(
                    id = "a1",
                    role = "assistant",
                    content = "",
                    timestamp = "2026-10-06T16:00:00Z",
                    runId = "run-1",
                    toolCalls = listOf(
                        UiToolCall(name = "Bash", arguments = "{}", result = null, status = "running"),
                    ),
                ),
            ),
        )
        val shell = FakeMascotShell(AGENT)
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
        onNode(
            hasTestTag(ComposerTestTags.TOUCH_COMPANION_STATUS) and
                hasAnyDescendant(hasText("Running Bash", substring = true)),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun presenceKeepsTheCompanionUpAfterStreamingFlagsDrop() = runComposeUiTest {
        val port = Port(typing = false)
        val shell = FakeMascotShell(AGENT)
        shell.registry.setPresence(AGENT, AgentPresence(activity = AgentActivityKind.WORKING, toolName = "grep"))
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
        onNodeWithTag(ComposerTestTags.TOUCH_COMPANION_STATUS).assertExists()
    }

    private companion object {
        const val AGENT = "agent-1"
        const val PHONE_WIDTH = 412
        const val PHONE_HEIGHT = 900
        const val SETTLE_MILLIS = 1_000L
        val PROMPT = UiMessage(
            id = "u1",
            role = "user",
            content = "Sketch a kitchen layout.",
            timestamp = "2026-10-06T16:00:00Z",
        )
    }
}
