@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.devfixtures.FixtureChatSessionPort
import com.letta.mobile.data.model.UiMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.lanes.ConversationLaneProjection
import com.letta.mobile.data.lanes.LaneActivitySource
import com.letta.mobile.data.lanes.LaneArrival
import com.letta.mobile.data.lanes.ConversationLane
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.chat.runtime.toChatConversationSummary
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellChatPort
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.components.LocalConversationLanes
import com.letta.mobile.ui.components.LocalConversationPurposes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

class ShellLaneAffordancesTest {
    @Test
    fun existingConversationRowRendersUnreadAndReset() = runComposeUiTest {
        val unread = mutableStateOf(2)
        setContent {
            val summary = ChatConversationSummary("inbox", "Inbox", "Main", "Remote", "", unreadCount = unread.value)
            val rows = ShellSidebarMapping.conversationRows(listOf(summary), ShellConversationMarks()) { "" }
            MaterialTheme {
                ShellAgentPanel(
                    ShellAgentPanelState(agent = ShellPanelAgent("Main"), conversations = rows),
                    ShellAgentPanelActions(), Modifier.width(320.dp),
                )
            }
        }
        onNodeWithText("2 unread").assertExists()
        runOnIdle { unread.value = 0 }
        onNodeWithText("2 unread").assertDoesNotExist()
    }

    @Test
    fun renderedActivitySurvivesViewReplacementAndReadAcknowledgesOnlyDisplayedIds() = runComposeUiTest {
        val rig = RenderRig()
        try {
            setContent { rig.Content() }
            rig.source.persisted("message-1")
            onNodeWithText("1 unread").assertExists()
            rig.source.persisted("message-1", "message-2")
            // The replayed message-1 is deduplicated: two distinct arrivals, two unread.
            onNodeWithText("2 unread").assertExists()
            onNodeWithText("human-home-message").assertExists()
            assertEquals(ConversationId("home"), rig.chat.selectedConversation)
            runOnIdle { rig.mounted.value = false }
            onNodeWithText("2 unread").assertDoesNotExist()
            runOnIdle { rig.mounted.value = true }
            onNodeWithText("2 unread").assertExists()
            onNodeWithText("Work · View only · x60fz").assertExists()
            onNodeWithText("Work record").performClick()
            assertEquals(ConversationId("work"), rig.chat.selectedConversation, "work remains viewable")
            onNodeWithText("Work transcript").assertExists()
            onNodeWithText("Work record · View only").assertExists()
            rig.navigator.sendToSelectedConversation("must not send")
            assertEquals(emptyList(), rig.chat.sent)
            rig.navigator.openAgent(AgentId("main"))
            assertEquals(ConversationId("home"), rig.chat.selectedConversation, "home remains the main-agent anchor")
            onNodeWithText("Agent messages").performClick()
            // The real shared ChatSurface acknowledges the loaded inbox identities after rendering.
            onNodeWithText("Inbox item message-2").assertExists()
            onNodeWithText("2 unread").assertDoesNotExist()
            onNodeWithText("1 unread").assertDoesNotExist()
            assertEquals(ConversationId("inbox"), rig.chat.selectedConversation)
            assertEquals(rig.homeBefore, rig.home.uiState.value, "inbox projection cannot rewrite the home timeline")
        } finally {
            rig.session.cancel()
        }
    }

    private inner class RenderRig {
        val source = TestActivitySource()
        val session = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = ConversationLaneProjection(session, source)
        val mounted = mutableStateOf(true)
        val home = FixtureChatSessionPort(
        ChatUiState(
            conversationState = ConversationState.Ready("home"),
            agentId = "main", isLoadingMessages = false,
            messages = persistentListOf(UiMessage("home-1", "user", "human-home-message", "2026-11-04T00:00:00Z")),
        ),
    )
        val homeBefore = home.uiState.value
        val records = listOf(
        Conversation(ConversationId("home"), AgentId("main"), summary = "Main conversation", tags = listOf("lane:home")),
        Conversation(ConversationId("inbox"), AgentId("main"), summary = "Agent messages", tags = listOf("lane:inbox")),
        Conversation(ConversationId("work"), AgentId("main"), summary = "Work record", tags = listOf("lane:work", "bead:x60fz")),
    )
        val workspace = ShellWorkspaceState(ShellDestination.Chat("main", "home"))
        val chat = RecordingChat(records)
        val navigator = ShellWorkspaceNavigator(workspace, chat)

        @Composable
        fun Content() {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalConversationLanes provides owner,
                    LocalConversationPurposes provides records.associate { it.id.value to ConversationLane.fromTags(it.tags) },
                ) {
                    if (mounted.value) ConversationWorkspace()
                }
            }
        }

        @Composable
        private fun ConversationWorkspace() {
            val rows = ShellSidebarMapping.conversationRows(
                records.map { it.toChatConversationSummary() }, ShellConversationMarks(),
            ) { "" }
            Row(Modifier.height(720.dp)) {
                ShellAgentPanel(
                    ShellAgentPanelState(agent = ShellPanelAgent("Main"), conversations = rows),
                    ShellAgentPanelActions(onConversationSelected = { navigator.openConversation(ConversationId(it)) }),
                    Modifier.width(360.dp),
                )
                val selected = chat.selectedConversation.value
                val port = remember(selected) {
                    if (selected == "home") home else transcriptPort(selected)
                }
                ChatSurface(
                    port, ChatSurfacePresentation.ChatFirst, {}, ChatSurfaceHost(), Modifier.width(600.dp),
                )
            }
        }
    }

    private fun transcriptPort(id: String): FixtureChatSessionPort {
        val messages = when (id) {
            "inbox" -> listOf("message-1", "message-2").map {
                UiMessage(it, "user", "Inbox item $it", "2026-11-04T00:00:00Z")
            }
            else -> listOf(UiMessage("work-1", "assistant", "Work transcript", "2026-11-04T00:00:00Z"))
        }
        return FixtureChatSessionPort(
            ChatUiState(
                conversationState = ConversationState.Ready(id),
                agentId = "main", isLoadingMessages = false, messages = messages.toPersistentList(),
            ),
        )
    }

    /**
     * Consumer-contract fixture standing in for the mr49w adapter (PR #2358 is unmerged): it emits
     * committed arrivals in that frame's shape. No count is assigned by the test; the projection counts.
     */
    private class TestActivitySource : LaneActivitySource {
        private val frames = MutableSharedFlow<LaneArrival>(extraBufferCapacity = 8)
        override fun arrivals() = frames
        fun persisted(vararg ids: String) = runBlocking {
            ids.forEach { frames.emit(LaneArrival("main", "inbox", it)) }
        }
    }

    private class RecordingChat(private val records: List<Conversation>) : ShellChatPort {
        private val selected = mutableStateOf(ConversationId("home"))
        override val selectedConversation: ConversationId get() = selected.value
        val sent = mutableListOf<String>()
        override fun conversations() = records.map { ShellConversationRef(it.id, it.agentId, "Remote", it.tags) }
        override fun selectConversation(conversation: ConversationId) { selected.value = conversation }
        override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) = error("existing home required")
        override fun startNewChat(agent: AgentId?) = error("existing home required")
        override fun send(conversation: ConversationId, text: String) { sent += text }
        override fun stageComposerText(text: String) = Unit
    }
}
