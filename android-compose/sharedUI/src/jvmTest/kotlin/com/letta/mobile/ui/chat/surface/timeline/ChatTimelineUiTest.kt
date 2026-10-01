@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.ui.chat.surface.RecordingChatActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.projection.IncrementalChatRenderItemsCache
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.render.GoalStatusUi
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared timeline end to end on desktop Compose (letta-mobile-bglj6.1). */
class ChatTimelineUiTest {

    private val ready = ChatUiState(conversationState = ConversationState.Ready("c1"), isLoadingMessages = false)

    private fun conversation(count: Int): List<UiMessage> = (0 until count).map { i ->
        UiMessage(
            id = "m$i",
            role = if (i % 2 == 0) "user" else "assistant",
            content = "message $i",
            timestamp = "2026-09-12T12:%02d:%02dZ".format(i / 60, i % 60),
        )
    }

    private fun rowCount(messages: List<UiMessage>): Int =
        timelineRowsNewestFirst(
            IncrementalChatRenderItemsCache().renderItems(messages, ChatDisplayMode.Interactive, ChatMessageListChange.Full, null),
        ).size

    private fun androidx.compose.ui.test.ComposeUiTest.show(state: ChatUiState, actions: RecordingChatActions) {
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 420.dp, height = 640.dp)) {
                    ChatTimeline(
                        state = state,
                        pagedTimeline = null,
                        actions = actions,
                        capabilities = ChatSurfaceCapabilities.Default,
                        host = ChatSurfaceHost(),
                        appearance = ChatSurfaceAppearance(),
                    )
                }
            }
        }
    }

    @Test
    fun loadingShowsTheSkeleton() = runComposeUiTest {
        show(ChatUiState(), RecordingChatActions())
        onNodeWithTag(ChatTimelineTags.SKELETON).assertExists()
        onNodeWithTag(ChatTimelineTags.LIST).assertDoesNotExist()
    }

    @Test
    fun aFailureOffersRetry() = runComposeUiTest {
        val actions = RecordingChatActions()
        show(ready.copy(conversationState = ConversationState.Error("Backend offline")), actions)
        onNodeWithTag(ChatTimelineTags.STATUS).assertExists()
        onNodeWithText("Backend offline").assertExists()
        onNodeWithText("Retry").performClick()
        assertEquals(1, actions.retries)
    }

    @Test
    fun aStarterPromptIsSent() = runComposeUiTest {
        val actions = RecordingChatActions()
        show(ready.copy(agentName = "Ada"), actions)
        onNodeWithTag(ChatTimelineTags.WELCOME).assertExists()
        onNodeWithText("Hi, I’m Ada").assertExists()
        onNodeWithText("How do I get started?").performClick()
        assertEquals(listOf("How do I get started?"), actions.sent)
    }

    @Test
    fun noConversationShowsStarterPromptsToo() = runComposeUiTest {
        val actions = RecordingChatActions()
        show(ready.copy(conversationState = ConversationState.NoConversation), actions)
        onNodeWithText("Start a conversation").assertExists()
        onNodeWithText("What can you help me with?").performClick()
        assertEquals(listOf("What can you help me with?"), actions.sent)
    }

    @Test
    fun messagesShowTheListFollowingTheLatest() = runComposeUiTest {
        show(ready.copy(messages = conversation(4).toPersistentList()), RecordingChatActions())
        onNodeWithTag(ChatTimelineTags.LIST).assertExists()
        onNodeWithTag(ChatTimelineTags.SCROLL_TO_LATEST).assertDoesNotExist()
    }

    @Test
    fun thinkingRowTrailsTheThreadWhileTheAgentWorks() = runComposeUiTest {
        show(ready.copy(messages = conversation(2).toPersistentList(), isAgentTyping = true), RecordingChatActions())
        onNodeWithTag(ChatTimelineTags.THINKING).assertExists()
    }

    @Test
    fun scrollingIntoHistoryOffersScrollToLatestWhichReturns() = runComposeUiTest {
        val messages = conversation(160)
        show(ready.copy(messages = messages.toPersistentList()), RecordingChatActions())
        onNodeWithTag(ChatTimelineTags.SCROLL_TO_LATEST).assertDoesNotExist()
        onNodeWithTag(ChatTimelineTags.LIST).performScrollToIndex(rowCount(messages) / 2)
        waitForIdle()
        onNodeWithTag(ChatTimelineTags.SCROLL_TO_LATEST).assertExists().performClick()
        waitForIdle()
        onNodeWithTag(ChatTimelineTags.SCROLL_TO_LATEST).assertDoesNotExist()
    }

    @Test
    fun reachingTheTopLoadsOlderHistory() = runComposeUiTest {
        val actions = RecordingChatActions()
        val messages = conversation(160)
        show(ready.copy(messages = messages.toPersistentList(), hasMoreOlderMessages = true), actions)
        assertEquals(0, actions.loadOlderCalls, "nothing loads while reading the newest messages")
        onNodeWithTag(ChatTimelineTags.LIST).performScrollToIndex(rowCount(messages) - 1)
        waitForIdle()
        assertTrue(actions.loadOlderCalls > 0)
    }

    @Test
    fun noOlderHistoryMeansNoLoad() = runComposeUiTest {
        val actions = RecordingChatActions()
        val messages = conversation(160)
        show(ready.copy(messages = messages.toPersistentList(), hasMoreOlderMessages = false), actions)
        onNodeWithTag(ChatTimelineTags.LIST).performScrollToIndex(rowCount(messages) - 1)
        waitForIdle()
        assertEquals(0, actions.loadOlderCalls)
    }

    @Test
    fun anErrorIsShownOnceThenCleared() = runComposeUiTest {
        val actions = RecordingChatActions()
        show(ready.copy(messages = conversation(2).toPersistentList(), error = "Send failed"), actions)
        waitUntil(timeoutMillis = 10_000) { actions.clearedErrors == 1 }
    }

    @Test
    fun goalCardSendsGoalCommands() = runComposeUiTest {
        val actions = RecordingChatActions()
        val goal = GoalStatusUi(objective = "Ship the shared page", status = "active", tokensUsed = 10)
        show(ready.copy(messages = conversation(2).toPersistentList(), goalStatus = goal), actions)
        onNodeWithTag(ChatTimelineTags.GOAL).assertExists()
        onNodeWithText("Pause").performClick()
        assertEquals(listOf(GoalCommands.PAUSE), actions.sent)
    }
}
