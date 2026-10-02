@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.ui.chat.surface.RecordingChatActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.mascot.FakeMascotShell
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertNotNull

/** The agent's mascot in the shared timeline (letta-mobile-bglj6.1): welcome hero, loading, gaze. */
class TimelineMascotUiTest {
    private val agent = "agent-1"

    private fun ComposeUiTest.show(shell: FakeMascotShell, state: ChatUiState) {
        setContent {
            shell.Provide {
                MaterialTheme {
                    Box(Modifier.size(width = 520.dp, height = 900.dp)) {
                        ChatTimeline(
                            state = state,
                            pagedTimeline = null,
                            actions = RecordingChatActions(),
                            capabilities = ChatSurfaceCapabilities.Default,
                            host = ChatSurfaceHost(),
                            appearance = ChatSurfaceAppearance(),
                        )
                    }
                }
            }
        }
    }

    private fun fresh(agentId: String?) = ChatUiState(
        conversationState = ConversationState.Ready("c1"),
        isLoadingMessages = false,
        agentId = agentId,
        agentName = "Ada",
    )

    @Test
    fun welcomeHeroGreetsAboveTheStarterPrompts() = runComposeUiTest {
        show(FakeMascotShell(agent), fresh(agent))
        onNodeWithTag(TimelineMascotTags.WELCOME_HERO).assertExists()
        onNodeWithText("Ada", substring = true).assertExists()
    }

    @Test
    fun welcomeKeepsItsPlainGreetingWithoutAMascot() = runComposeUiTest {
        show(FakeMascotShell(), fresh(agent))
        onNodeWithTag(TimelineMascotTags.WELCOME_HERO).assertDoesNotExist()
        onNodeWithText("Ada", substring = true).assertExists()
    }

    @Test
    fun loadingShowsTheAgentInsteadOfTheSkeleton() = runComposeUiTest {
        show(FakeMascotShell(agent), ChatUiState(agentId = agent))
        onNodeWithTag(TimelineMascotTags.LOADING).assertExists()
        onNodeWithTag(ChatTimelineTags.SKELETON).assertDoesNotExist()
    }

    @Test
    fun loadingFallsBackToTheSkeletonWithoutAMascot() = runComposeUiTest {
        show(FakeMascotShell(), ChatUiState(agentId = agent))
        onNodeWithTag(ChatTimelineTags.SKELETON).assertExists()
    }

    @Test
    fun listPublishesItsGazeTargetAndTheMascotHidesTheThinkingRow() = runComposeUiTest {
        val shell = FakeMascotShell(agent)
        val messages = persistentListOf(
            UiMessage(id = "m0", role = "user", content = "hello", timestamp = "2026-09-12T12:00:00Z"),
        )
        show(shell, fresh(agent).copy(messages = messages, isAgentTyping = true))
        waitForIdle()
        onNodeWithTag(ChatTimelineTags.LIST).assertExists()
        assertNotNull(shell.registry.timelineBounds.value, "the list publishes where the mascot can look")
        onNodeWithTag(ChatTimelineTags.THINKING).assertDoesNotExist()
    }
}
