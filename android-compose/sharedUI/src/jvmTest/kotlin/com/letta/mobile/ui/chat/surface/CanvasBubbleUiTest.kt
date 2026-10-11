@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.branch.PendingComposerDrafts
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.chat.surface.recents.ChatRecentInteractions
import com.letta.mobile.ui.chat.surface.recents.RECENTS_EMPTY_TAG
import com.letta.mobile.ui.chat.surface.recents.RECENTS_LIST_TAG
import com.letta.mobile.ui.chat.surface.recents.RECENTS_NEW_THREAD_TAG
import com.letta.mobile.ui.chat.surface.recents.recentRowTag
import com.letta.mobile.ui.shell.sidebar.ShellConversationRowModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-y5q9z: the phone's canvas mode is the agent bubble, not a bottom bar. Collapsed: the
 * head and its latest-message bubble. Tapped: a card with the exchange and the prompt, the board still
 * there. Its "+" lists the agent's recent interactions (archived ones too) to hop to, or a new thread.
 * Desktop's docked panel gets the same "+".
 */
class CanvasBubbleUiTest {
    private val prompt = UiMessage(id = "u1", role = "user", content = "Sketch a kitchen layout.", timestamp = "2026-09-30T18:02:00Z")
    private val reply = UiMessage(id = "a1", role = "assistant", content = "Here's an L-shaped plan.", timestamp = "2026-09-30T18:02:09Z", runId = "run-1")

    private class Port : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = persistentListOf(),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = "agent-1",
            ),
        )
        override val composer = MutableStateFlow(
            ChatComposerUiState(canSend = true, model = ChatModelUiState(currentHandle = "m", currentLabel = "Model")),
        )
        override val actions: ChatActions = RecordingChatActions()
        val recorded: RecordingChatActions get() = actions as RecordingChatActions
    }

    private class Hops {
        val opened = mutableListOf<String>()
        var newThreads = 0
        val intents = mutableListOf<ChatSurfaceIntent>()
    }

    private fun recents(hops: Hops, rows: List<ShellConversationRowModel> = defaultRows) = ChatRecentInteractions(
        conversations = rows,
        onOpenConversation = { hops.opened += it },
        onNewThread = { hops.newThreads++ },
    )

    private val defaultRows = listOf(
        ShellConversationRowModel(id = "conv-1", title = "Kitchen plan", preview = "", timeLabel = "now", selected = true),
        ShellConversationRowModel(id = "conv-2", title = "Garden ideas", preview = "", timeLabel = "2h"),
        ShellConversationRowModel(id = "conv-3", title = "Old sketch", preview = "", timeLabel = "3w", archived = true),
    )

    @AfterTest
    fun spendArrival() {
        CanvasBubbleArrival.take()
        PendingComposerDrafts.shared.take("conv-1")
    }

    private fun ComposeUiTest.showTouch(port: Port, hops: Hops, recents: ChatRecentInteractions? = recents(hops)) {
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = port,
                    presentation = ChatSurfacePresentation.CanvasFirst,
                    onIntent = { hops.intents += it },
                    host = ChatSurfaceHost(openCanvas = {}),
                    modifier = Modifier.fillMaxSize(),
                    appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                    platform = ChatSurfacePlatform(showKeyboardHints = false),
                    canvas = { _ -> Box(Modifier.fillMaxSize().testTag(BOARD_TAG)) },
                    recents = recents,
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun collapsedThereIsNoBottomBarOnlyTheHeadAndItsBubble() = runComposeUiTest {
        val port = Port()
        port.uiState.value = port.uiState.value.copy(messages = persistentListOf(prompt, reply))
        showTouch(port, Hops())
        onAllNodesWithTag(ComposerTestTags.TOUCH_BAR).assertCountEquals(0)
        onNodeWithTag(TOUCH_HEAD_TAG).assertExists()
        onNodeWithTag(TOUCH_POPUP_TAG).assertExists()
        onAllNodesWithTag(BUBBLE_CARD_TAG).assertCountEquals(0)
        onAllNodesWithTag(BUBBLE_PLUS_TAG).assertCountEquals(0)
    }

    @Test
    fun tappingTheBubbleOpensTheCardOverTheBoardAndItsCollapseFoldsIt() = runComposeUiTest {
        val port = Port()
        port.uiState.value = port.uiState.value.copy(messages = persistentListOf(prompt, reply))
        val hops = Hops()
        showTouch(port, hops)
        onNodeWithTag(TOUCH_POPUP_TAG).performClick()
        waitForIdle()
        onNodeWithTag(BUBBLE_CARD_TAG).assertExists()
        onNodeWithTag(ComposerTestTags.TOUCH_BAR).assertExists()
        onNodeWithTag(DOCKED_REPLY_TAG).assertExists()
        onNodeWithTag(BOARD_TAG).assertExists()
        onAllNodesWithTag(TOUCH_POPUP_TAG).assertCountEquals(0)
        assertTrue(hops.intents.isEmpty(), "the bubble opened the full chat: ${hops.intents}")
        onNodeWithTag(BUBBLE_COLLAPSE_TAG).performClick()
        waitForIdle()
        onAllNodesWithTag(BUBBLE_CARD_TAG).assertCountEquals(0)
        onAllNodesWithTag(ComposerTestTags.TOUCH_BAR).assertCountEquals(0)
    }

    @Test
    fun theHeadOpensAndFoldsTheCardAndTheCardsChevronOpensTheFullChat() = runComposeUiTest {
        val hops = Hops()
        showTouch(Port(), hops)
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        onNodeWithTag(BUBBLE_CARD_TAG).assertExists()
        onNodeWithTag(ComposerTestTags.TOUCH_RESTORE).performClick()
        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.Expand), hops.intents)
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        onAllNodesWithTag(BUBBLE_CARD_TAG).assertCountEquals(0)
    }

    @Test
    fun thePlusListsTheRecentInteractionsAndHopsCarryingTheDraft() = runComposeUiTest {
        val port = Port()
        port.composer.value = port.composer.value.copy(text = "half a thought")
        val hops = Hops()
        showTouch(port, hops)
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        onNodeWithTag(BUBBLE_PLUS_TAG).performClick()
        waitForIdle()
        onNodeWithTag(RECENTS_LIST_TAG).assertExists()
        onNodeWithTag(recentRowTag("conv-3")).assertExists()
        onNodeWithTag(recentRowTag("conv-2")).performClick()
        waitForIdle()
        assertEquals(listOf("conv-2"), hops.opened)
        assertEquals("half a thought", PendingComposerDrafts.shared.take("conv-1"), "the draft waits for the way back")
        assertTrue(CanvasBubbleArrival.take(), "the next conversation's card opens on arrival")
        onAllNodesWithTag(RECENTS_LIST_TAG).assertCountEquals(0)
        onNodeWithTag(BUBBLE_CARD_TAG).assertExists()
    }

    @Test
    fun pickingTheCurrentConversationOnlyClosesTheList() = runComposeUiTest {
        val hops = Hops()
        showTouch(Port(), hops)
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        onNodeWithTag(BUBBLE_PLUS_TAG).performClick()
        waitForIdle()
        onNodeWithTag(recentRowTag("conv-1")).performClick()
        waitForIdle()
        assertTrue(hops.opened.isEmpty())
        onAllNodesWithTag(RECENTS_LIST_TAG).assertCountEquals(0)
    }

    @Test
    fun anEmptyListStillOffersANewThread() = runComposeUiTest {
        val hops = Hops()
        showTouch(Port(), hops, recents(hops, rows = emptyList()))
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        onNodeWithTag(BUBBLE_PLUS_TAG).performClick()
        waitForIdle()
        onNodeWithTag(RECENTS_EMPTY_TAG).assertExists()
        onNodeWithTag(RECENTS_NEW_THREAD_TAG).performClick()
        assertEquals(1, hops.newThreads)
    }

    @Test
    fun withoutRecentsThereIsNoPlus() = runComposeUiTest {
        showTouch(Port(), Hops(), recents = null)
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        onNodeWithTag(BUBBLE_CARD_TAG).assertExists()
        onAllNodesWithTag(BUBBLE_PLUS_TAG).assertCountEquals(0)
    }

    @Test
    fun aRunningTurnShowsInTheCollapsedBubbleWithStop() = runComposeUiTest {
        val port = Port()
        port.uiState.value = port.uiState.value.copy(messages = persistentListOf(prompt), isStreaming = true)
        // The working line's clock ticks while the turn runs, so the page never idles: step the clock.
        mainClock.autoAdvance = false
        showTouch(port, Hops())
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        onNodeWithTag(TOUCH_POPUP_TAG).assertExists()
        onNodeWithTag(BUBBLE_STOP_TAG).performClick()
        assertEquals(1, port.recorded.count("stopRun"))
    }

    @Test
    fun theCardIsAsItWasLeftOnTheWayBackFromTheFullChat() = runComposeUiTest {
        var presentation by mutableStateOf(ChatSurfacePresentation.CanvasFirst)
        val hops = Hops()
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = Port(),
                    presentation = presentation,
                    onIntent = {},
                    host = ChatSurfaceHost(openCanvas = {}),
                    modifier = Modifier.fillMaxSize(),
                    appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                    platform = ChatSurfacePlatform(showKeyboardHints = false),
                    canvas = { _ -> Box(Modifier.fillMaxSize()) },
                    recents = recents(hops),
                )
            }
        }
        waitForIdle()
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        presentation = ChatSurfacePresentation.ChatFirst
        waitForIdle()
        onAllNodesWithTag(BUBBLE_CARD_TAG).assertCountEquals(0)
        presentation = ChatSurfacePresentation.CanvasFirst
        waitForIdle()
        onNodeWithTag(BUBBLE_CARD_TAG).assertExists()
    }

    @Test
    fun normalChatKeepsItsComposer() = runComposeUiTest {
        val hops = Hops()
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = Port(),
                    presentation = ChatSurfacePresentation.ChatFirst,
                    onIntent = {},
                    host = ChatSurfaceHost(),
                    modifier = Modifier.fillMaxSize(),
                    appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                    platform = ChatSurfacePlatform(showKeyboardHints = false),
                    recents = recents(hops),
                )
            }
        }
        waitForIdle()
        onNodeWithTag(ComposerTestTags.TOUCH_BAR).assertExists()
        onAllNodesWithTag(BUBBLE_PLUS_TAG).assertCountEquals(0)
    }

    @Test
    fun theDesktopPanelsPlusHopsBetweenConversations() = runComposeUiTest {
        val hops = Hops()
        setContent {
            MaterialTheme {
                ChatSurface(
                    port = Port(),
                    presentation = ChatSurfacePresentation.CanvasFirst,
                    onIntent = {},
                    host = ChatSurfaceHost(openCanvas = {}),
                    modifier = Modifier.fillMaxSize(),
                    canvas = { _ -> Box(Modifier.fillMaxSize()) },
                    recents = recents(hops),
                )
            }
        }
        waitForIdle()
        onNodeWithTag(DOCK_RECENTS_TAG).performClick()
        waitForIdle()
        onNodeWithTag(RECENTS_LIST_TAG).assertExists()
        onNodeWithTag(RECENTS_NEW_THREAD_TAG).performClick()
        waitForIdle()
        assertEquals(1, hops.newThreads)
        onAllNodesWithTag(RECENTS_LIST_TAG).assertCountEquals(0)
        onNodeWithTag(DOCK_RECENTS_TAG).performClick()
        waitForIdle()
        onNodeWithTag(recentRowTag("conv-2")).performClick()
        assertEquals(listOf("conv-2"), hops.opened)
    }

    private companion object {
        const val BOARD_TAG = "test-board"
        const val SETTLE_MILLIS = 1_500L
    }
}
