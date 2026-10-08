@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.ui.chat.surface.RecordingChatActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.projection.IncrementalChatRenderItemsCache
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.render.GoalStatusUi
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getBoundsInRoot
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared timeline end to end on desktop Compose (letta-mobile-bglj6.1). */
class ChatTimelineUiTest {

    private val ready = ChatUiState(conversationState = ConversationState.Ready("c1"), isLoadingMessages = false)

    /** Half the 640 dp test viewport. */
    private val halfViewportDp = 320f

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

    private fun androidx.compose.ui.test.ComposeUiTest.show(
        state: ChatUiState,
        actions: RecordingChatActions,
        host: ChatSurfaceHost = ChatSurfaceHost(),
        capabilities: ChatSurfaceCapabilities = ChatSurfaceCapabilities.Default,
    ) {
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 420.dp, height = 640.dp)) {
                    ChatTimeline(
                        state = state,
                        pagedTimeline = null,
                        actions = actions,
                        capabilities = capabilities,
                        host = host,
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

    /** A nudge a few rows into history is not "far from the latest": the button waits for real distance. */
    @Test
    fun aNudgeIntoHistoryDoesNotOfferScrollToLatest() = runComposeUiTest {
        show(ready.copy(messages = conversation(160).toPersistentList()), RecordingChatActions())
        // Reversed list: dragging down reads older rows. Held still before lifting, so nothing flings.
        onNodeWithTag(ChatTimelineTags.LIST).performTouchInput {
            down(center)
            repeat(NUDGE_STEPS) { moveBy(Offset(0f, NUDGE_STEP_PX)) }
            advanceEventTime(HOLD_BEFORE_LIFT_MILLIS)
            up()
        }
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
    fun goalCardSendsGoalCommands() = runComposeUiTest {
        val actions = RecordingChatActions()
        val goal = GoalStatusUi(objective = "Ship the shared page", status = "active", tokensUsed = 10)
        show(ready.copy(messages = conversation(2).toPersistentList(), goalStatus = goal), actions)
        onNodeWithTag(ChatTimelineTags.GOAL).assertExists()
        onNodeWithText("Pause").performClick()
        assertEquals(listOf(GoalCommands.PAUSE), actions.sent)
    }

    /** letta-mobile-bglj6.1.22: thumb reach on Touch, as the legacy composer column; the top on a pointer host. */
    @Test
    fun goalCardSitsAtTheBottomOnTouchAndTheTopOtherwise() = runComposeUiTest {
        val goal = GoalStatusUi(objective = "Ship the shared page", status = "active", tokensUsed = 10)
        var style by mutableStateOf(ChatPlatformStyle.Touch)
        setContent {
            CompositionLocalProvider(LocalChatPlatformStyle provides style) {
                MaterialTheme {
                    Box(Modifier.size(width = 420.dp, height = 640.dp)) {
                        ChatTimeline(
                            state = ready.copy(messages = conversation(2).toPersistentList(), goalStatus = goal),
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
        val touch = onNodeWithTag(ChatTimelineTags.GOAL).getBoundsInRoot()
        assertTrue(touch.bottom.value > halfViewportDp, "the goal card is not at the bottom on Touch: $touch")
        runOnIdle { style = ChatPlatformStyle.Pointer }
        val pointer = onNodeWithTag(ChatTimelineTags.GOAL).getBoundsInRoot()
        assertTrue(pointer.top.value < halfViewportDp, "the goal card is not at the top on a pointer host: $pointer")
    }

    @Test
    fun goalRefreshAndContinueShowOnlyForOwnersWithGoals() = runComposeUiTest {
        val goal = GoalStatusUi(objective = "Ship the shared page", status = "active", tokensUsed = 10)
        show(ready.copy(messages = conversation(2).toPersistentList(), goalStatus = goal), RecordingChatActions())
        onNodeWithText("Refresh").assertDoesNotExist()
        onNodeWithText("Continue").assertDoesNotExist()
    }

    @Test
    fun goalRefreshAndContinueReachTheOwner() = runComposeUiTest {
        val actions = RecordingChatActions()
        val goal = GoalStatusUi(objective = "Ship the shared page", status = "active", tokensUsed = 10)
        show(
            ready.copy(messages = conversation(2).toPersistentList(), goalStatus = goal),
            actions,
            capabilities = ChatSurfaceCapabilities(goals = true),
        )
        onNodeWithText("Refresh").performClick()
        onNodeWithText("Continue").performClick()
        runOnIdle {
            assertEquals(1, actions.count("refreshGoalStatus"))
            assertEquals(1, actions.count("continueGoal"))
        }
    }

    @Test
    fun subagentRowsOpenThroughTheHost() = runComposeUiTest {
        val opened = mutableListOf<Triple<String, String?, String>>()
        val dispatch = UiSubagentDispatch(
            toolCallId = "agent-call-1",
            description = "Audit the build",
            subagentType = "general-purpose",
            runInBackground = false,
            prompt = "Look at gradle",
            subagentAgentId = "agent-sub",
        )
        val call = UiToolCall(name = "Agent", arguments = "{}", result = null, status = "running", subagentDispatch = dispatch)
        val messages = conversation(1) + UiMessage(
            id = "m-dispatch",
            role = "assistant",
            content = "",
            timestamp = "2026-09-12T12:01:00Z",
            toolCalls = listOf(call),
        )
        val host = ChatSurfaceHost(openSubagent = { callId, agentId, description -> opened += Triple(callId, agentId, description) })
        show(ready.copy(messages = messages.toPersistentList()), RecordingChatActions(), host)

        // The dispatch reads as one tool summary line; its card opens from it.
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).performClick()
        onNodeWithText("Dispatched: Audit the build").performClick()
        runOnIdle { assertEquals(listOf<Triple<String, String?, String>>(Triple("agent-call-1", "agent-sub", "Audit the build")), opened) }
    }

    /**
     * The owning prompt pins over a long answer only while no prompt is on screen: with a newer
     * prompt in view, a pinned copy would sit over it (two "You" bubbles overlapping).
     */
    @Test
    fun theOwningPromptPinsOnlyWhileNoPromptIsOnScreen() = runComposeUiTest {
        val longAnswer = (1..120).joinToString("\n\n") { "Paragraph $it of a long answer." }
        val messages = listOf(
            UiMessage(id = "q1", role = "user", content = "First question", timestamp = "2026-09-12T12:00:00Z"),
            UiMessage(id = "a1", role = "assistant", content = longAnswer, timestamp = "2026-09-12T12:00:10Z"),
            UiMessage(id = "q2", role = "user", content = "Second question", timestamp = "2026-09-12T12:01:00Z"),
            UiMessage(id = "a2", role = "assistant", content = "Short answer.", timestamp = "2026-09-12T12:01:10Z"),
        )
        show(ready.copy(messages = messages.toPersistentList()), RecordingChatActions())

        // At the newest edge the second prompt is in view under the long answer's tail.
        onNodeWithText("Second question").assertExists()
        onNodeWithTag(ChatTimelineTags.PINNED_PROMPT).assertDoesNotExist()

        // Reversed list: index 2 is the long answer, which alone fills the viewport.
        // The markdown lays out off the main thread: wait for the answer's full height first.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithText("Paragraph 120", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ChatTimelineTags.LIST).performScrollToIndex(2)
        // That leaves the second prompt peeking through the list's bottom padding (still on
        // screen, so still no pin); a short drag toward older history takes it off screen.
        onNodeWithTag(ChatTimelineTags.LIST).performTouchInput {
            swipe(start = center, end = center.copy(y = center.y + 200f), durationMillis = 400L)
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ChatTimelineTags.PINNED_PROMPT).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        /** Six 40px steps: about 220px past the touch slop, a few rows but under the button's show distance (40% of 640dp). */
        const val NUDGE_STEPS = 6
        const val NUDGE_STEP_PX = 40f
        const val HOLD_BEFORE_LIFT_MILLIS = 500L
    }
}
