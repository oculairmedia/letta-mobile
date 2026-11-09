@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.desktopshell.ShellLayoutStore
import com.letta.mobile.data.chat.conversations.TimelineConversations
import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.conversations.ConversationManagementPrefs
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.SessionShellChatPort
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.shell.layout.ShellLayoutConfigStore
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.workspace.ShellLayoutController
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderBinding
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceSearch
import com.letta.mobile.ui.shell.workspace.rememberShellWorkspaceHeader
import com.letta.mobile.ui.shell.workspace.workspaceNavigation
import com.letta.mobile.ui.shell.conversations.SyncTimelineConversations
import com.letta.mobile.ui.shell.conversations.ShellTimelineChatBinding
import com.letta.mobile.ui.shell.conversations.TimelineConversationPreferences
import com.letta.mobile.ui.shell.conversations.rememberShellTimelineChat
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow

class ShellWorkspaceSyncTest {
    private class TestPort(val conversation: String) : ChatSessionPort {
        override val uiState = MutableStateFlow(ChatUiState())
        override val composer = MutableStateFlow(ChatComposerUiState())
        override val actions = RecordingChatActions()
    }

    @Test
    fun switchingSessionRecreatesTheTimelineEvenWhenTheConnectionAndIdsMatch() = runComposeUiTest {
        val agent = AgentId("agent-a")
        val rows = listOf(Conversation(ConversationId("conversation-a"), agent))
        val agents = listOf(Agent(agent, name = "A"))
        val workspace = ShellWorkspaceState(ShellDestination.Chat("agent-a", "conversation-a")).apply { bindSource("source-a", 1) }
        val session = mutableStateOf("source-a")
        val gatewayCalls = AtomicInteger()
        var owner: TimelineConversations? = null
        setContent {
            val binding = ShellTimelineChatBinding(
                workspace, session.value, BackendConnectionStatus.Connected("Iroh"), agents,
                TimelineConversationPreferences(ConversationManagementPrefs()), backend = {
                    gatewayCalls.incrementAndGet()
                    gateway(rows)
                },
                open = { _, target, _ -> TestPort(target.conversationId) }, close = {},
            )
            val chat = rememberShellTimelineChat(binding)
            SideEffect { owner = chat.owner }
        }
        waitUntil(timeoutMillis = 10_000) { owner != null }
        val original = assertNotNull(owner)
        runOnIdle {
            workspace.bindSource("source-b", 2)
            workspace.navigate(ShellDestination.Chat("agent-a", "conversation-a"))
            session.value = "source-b"
        }
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { owner != null }
        assertNotSame(original, owner)
        assertEquals(2, gatewayCalls.get())
    }

    @Test
    fun aChangedFocusNeverExposesThePreviousConversationPort() = runComposeUiTest {
        val agentA = AgentId("agent-a")
        val agentB = AgentId("agent-b")
        val rows = listOf(Conversation(ConversationId("conversation-a"), agentA), Conversation(ConversationId("conversation-b"), agentB))
        val agents = listOf(Agent(agentA, name = "A"), Agent(agentB, name = "B"))
        val workspace = ShellWorkspaceState(ShellDestination.Chat("agent-a", "conversation-a")).apply { bindSource("source-a", 1) }
        val frames = CopyOnWriteArrayList<Pair<String?, String?>>()
        val binding = ShellTimelineChatBinding(
            workspace, "source-a", BackendConnectionStatus.Connected("Iroh"), agents,
            TimelineConversationPreferences(ConversationManagementPrefs()), backend = { gateway(rows) },
            open = { _, target, _ -> TestPort(target.conversationId) }, close = {},
        )
        setContent {
            val chat = rememberShellTimelineChat(binding)
            val focus = workspace.currentFocus.conversationId?.value
            val port = chat.port?.conversation
            SideEffect { frames += focus to port }
        }
        waitUntil(timeoutMillis = 10_000) { frames.any { it == ("conversation-a" to "conversation-a") } }
        runOnIdle { workspace.navigate(ShellDestination.Chat("agent-b", "conversation-b")) }
        waitForIdle()
        assertEquals(ConversationId("conversation-b"), workspace.currentFocus.conversationId)
        assertTrue(frames.none { (focus, port) -> port != null && port != focus }, "a port must match committed focus in every frame")
        waitUntil(timeoutMillis = 10_000) { frames.any { it == ("conversation-b" to "conversation-b") } }
        assertTrue(frames.none { (focus, port) -> port != null && port != focus }, "the new port must not expose the old target")
    }

    @Test
    fun missingConversationStillHasTheOwnersActiveTab() = runComposeUiTest {
        val workspace = ShellWorkspaceState(ShellDestination.Chat("agent-b", "conversation-b"))
        val navigator = ShellWorkspaceNavigator(workspace, SessionShellChatPort())
        val settings = InMemorySecureSettingsStore()
        val layout = ShellLayoutController(ShellLayoutStore(settings, "test"), ShellLayoutConfigStore(settings, "test.lanes"))
        var header: ShellWorkspaceHeaderState? = null
        setContent {
            header = rememberShellWorkspaceHeader(
                ShellWorkspaceHeaderBinding(navigator, layout, ShellWorkspaceSearch(null, UnifiedSearchSources())) { null },
            )
        }
        waitForIdle()
        assertEquals("conversation-b", header?.tabs?.activeKey)
        assertEquals(listOf("conversation-b"), header?.tabs?.tabs?.map { it.key })
        assertEquals("Chat", header?.tabs?.tabs?.single()?.title)
    }

    @Test
    fun timelineSelectionCannotReplaceTheCommittedWorkspaceTarget() = runComposeUiTest {
        val agentA = AgentId("agent-a")
        val agentB = AgentId("agent-b")
        val rows = listOf(Conversation(ConversationId("conversation-a"), agentA), Conversation(ConversationId("conversation-b"), agentB))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val agents = listOf(Agent(agentA, name = "A"), Agent(agentB, name = "B"))
            val owner = TimelineConversations(scope, gateway(rows), agents)
            waitUntil(timeoutMillis = 10_000) { owner.state.value.loaded }
            owner.selectConversation(ConversationId("conversation-a"))
            val workspace = ShellWorkspaceState(ShellDestination.Chat("agent-b", "conversation-b")).apply {
                bindSource("source-a", 1)
            }
            val before = workspace.currentFocus
            setContent { SyncTimelineConversations(workspace, owner, "source-a", agents) }
            waitForIdle()
            assertEquals(before, workspace.currentFocus)
            assertEquals(ShellDestination.Chat("agent-b", "conversation-b"), workspace.destination)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loadingARecentRowDoesNotChooseAResumeTarget() = runComposeUiTest {
        val agent = AgentId("agent-b")
        val rows = listOf(Conversation(ConversationId("conversation-b"), agent))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val agents = listOf(Agent(agent, name = "B"))
            val owner = TimelineConversations(scope, gateway(rows), agents)
            waitUntil(timeoutMillis = 10_000) { owner.state.value.loaded }
            val workspace = ShellWorkspaceState(ShellDestination.Chat("agent-b")).apply { bindSource("source-a", 1) }
            setContent { SyncTimelineConversations(workspace, owner, "source-a", agents) }
            waitForIdle()
            assertEquals(null, owner.selectedConversation)
            assertEquals(null, workspace.currentFocus.conversationId)
            assertEquals(agent, workspace.currentFocus.agentId)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleAndNullChatEmissionsDoNotChangeNavigationOrTabs() = runComposeUiTest {
        val workspace = ShellWorkspaceState(ShellDestination.Chat()).apply {
            bindSource("source-a", 1)
            navigate(ShellDestination.Chat("agent-b", "conversation-b"))
            tabs = ConversationTabsState(listOf("conversation-a"), "conversation-b")
        }
        val emission = mutableStateOf(ShellChatSelection("source-a", null, null, emptySet()))
        val roster = listOf("agent-a" to "A", "agent-b" to "B")
        val now = Instant.parse("2026-10-11T12:00:00Z")
        val before = workspace.workspaceNavigation(roster, now)
        val tabs = workspace.tabs
        setContent { SyncShellWorkspace(workspace, emission.value) }
        waitForIdle()
        runOnIdle {
            assertEquals(before, workspace.workspaceNavigation(roster, now))
            assertEquals(tabs, workspace.tabs)
            emission.value = ShellChatSelection("source-a", ConversationId("conversation-a"), AgentId("agent-a"), emptySet())
        }
        waitForIdle()
        runOnIdle {
            assertEquals(before, workspace.workspaceNavigation(roster, now))
            assertEquals(tabs, workspace.tabs)
            assertEquals(ShellDestination.Chat("agent-b", "conversation-b"), workspace.destination)
        }
    }

    private fun gateway(rows: List<Conversation>): ChatGateway {
        val unexpected = Proxy.newProxyInstance(ChatGateway::class.java.classLoader, arrayOf(ChatGateway::class.java)) {
                _, method, _ -> error("Unexpected gateway call: ${method.name}")
        } as ChatGateway
        return object : ChatGateway by unexpected {
            override val conversationChanges = emptyFlow<Pair<ConversationId, AgentId?>>()
            override val deleteBehavior = ConversationDeleteBehavior.Permanent
            override suspend fun listRecentConversations(limit: Int, after: ConversationId?): List<Conversation> {
                require(limit == 30 && after == null)
                return rows
            }
            override suspend fun listAgentConversations(page: AgentConversationPage): List<Conversation> {
                require(rows.any { it.agentId == page.agent })
                require(page.after == null && page.archiveStatus == "all")
                return rows.filter { it.agentId == page.agent }.take(page.limit)
            }
            override suspend fun getConversation(conversationId: String): Conversation = rows.single { it.id.value == conversationId }
        }
    }
}
