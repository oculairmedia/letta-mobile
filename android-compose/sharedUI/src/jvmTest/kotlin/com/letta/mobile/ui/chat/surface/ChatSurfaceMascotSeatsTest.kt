@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
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
import com.letta.mobile.ui.chat.surface.timeline.TimelineMascotTags
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.MascotTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-ckmub.1 (hard cut non-negotiable): the desktop mascot survives the cut. The shared
 * chat page (ChatSurface, full screen as desktop and the Android flip show it) must keep the
 * agent's three mascot seats: the COMPOSER_COMPANION seat once a conversation runs, the
 * WELCOME_HERO seat on a fresh conversation, and MascotLoading while the first page loads. A
 * change that drops any of them fails here before the legacy surfaces are deleted.
 */
class ChatSurfaceMascotSeatsTest {
    private val agent = "agent-1"

    private class Port(state: ChatUiState) : ChatSessionPort {
        override val uiState: StateFlow<ChatUiState> = MutableStateFlow(state)
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(ChatComposerUiState(canSend = true))
        override val actions: ChatActions = RecordingChatActions()
    }

    private fun ComposeUiTest.showPage(shell: FakeMascotShell, state: ChatUiState) {
        // MascotLoading's ring never stops turning: drive the clock by hand.
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                MaterialTheme {
                    Box(Modifier.size(width = PHONE_WIDTH.dp, height = PHONE_HEIGHT.dp)) {
                        ChatSurface(
                            port = Port(state),
                            presentation = ChatSurfacePresentation.ChatFirst,
                            onIntent = {},
                            host = ChatSurfaceHost(),
                        )
                    }
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        waitForIdle()
    }

    private fun ready(messages: List<UiMessage>) = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        messages = persistentListOf<UiMessage>().addAll(messages),
        isLoadingMessages = false,
        agentId = agent,
        agentName = "Meridian",
    )

    @Test
    fun aRunningConversationSeatsTheComposerCompanion() = runComposeUiTest {
        val shell = FakeMascotShell(agent)
        val prompt = UiMessage(id = "u1", role = "user", content = "Run the chat tests.", timestamp = "2026-10-11T16:02:00Z")
        val reply = UiMessage(id = "a1", role = "assistant", content = "They pass.", timestamp = "2026-10-11T16:02:09Z", runId = "run-1")
        showPage(shell, ready(listOf(prompt, reply)))
        assertNotNull(
            shell.transport.seat(MascotTransport.SeatKey(agent, MascotStage.COMPOSER_COMPANION)),
            "the page declares the COMPOSER_COMPANION seat",
        )
        assertEquals(MascotStage.COMPOSER_COMPANION, shell.transport.activeStage(agent))
    }

    @Test
    fun aFreshConversationSeatsTheWelcomeHero() = runComposeUiTest {
        val shell = FakeMascotShell(agent)
        showPage(shell, ready(emptyList()))
        onNodeWithTag(TimelineMascotTags.WELCOME_HERO).assertExists()
        assertNotNull(
            shell.transport.seat(MascotTransport.SeatKey(agent, MascotStage.WELCOME_HERO)),
            "the welcome declares the WELCOME_HERO seat",
        )
        assertEquals(MascotStage.WELCOME_HERO, shell.transport.activeStage(agent))
    }

    @Test
    fun theFirstLoadShowsMascotLoading() = runComposeUiTest {
        showPage(FakeMascotShell(agent), ChatUiState(agentId = agent, agentName = "Meridian"))
        onNodeWithTag(TimelineMascotTags.LOADING).assertExists()
    }

    private companion object {
        const val PHONE_WIDTH = 412
        const val PHONE_HEIGHT = 915
        const val SETTLE_MILLIS = 1_000L
    }
}
