package com.letta.mobile.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.session.SessionGraph
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.ui.chat.subagents.SubagentRosterTag
import com.letta.mobile.ui.screens.config.SettingsDestinations
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceDrawer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Background tasks sheet opens a running subagent's own chat: its "View conversation" closes the
 * sheet, focuses the child agent's conversation exactly (the registry's bare `default` resolved to
 * the addressable `conv-default-<agentId>` form), and lands on the chat page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class AndroidBackgroundTasksSubagentChatTest {
    @get:Rule val compose = createComposeRule()
    private val config = LettaConfig("config", LettaConfig.Mode.SELF_HOSTED, "https://a.invalid", accessToken = "principal-a")
    private val parent = AgentId("agent-parent")
    private val parentConversation = ConversationId("conv-parent")
    private val childAgentId = "agent-local-159eae27-cbed-4570-b9b6-1fcd08bbc720"
    private val running = SubagentEntry(
        toolCallId = "toolu_run",
        description = "Improve the roster",
        subagentType = "General-purpose",
        status = "running",
        parentAgentId = parent.value,
        parentConversationId = parentConversation.value,
        subagentAgentId = childAgentId,
        subagentConversationId = "default",
        startedAt = "1791642000000",
        toolCallCount = 116,
        totalTokens = 1_234_567L,
        model = "lmstudio/vibe-glm-5-3",
    )
    private var dismissed: ToolCallId? = null
    private val repository = object : ISubagentRepository {
        override fun activeSubagentsFlow(scope: SubagentParentScope) = flowOf(listOf(running))
        override fun currentActiveSubagents(scope: SubagentParentScope) = listOf(running)
        override fun subagentsWithHistoryFlow(scope: SubagentParentScope) = flowOf(listOf(running))
        override fun currentSubagentsWithHistory(scope: SubagentParentScope) = listOf(running)
        override suspend fun refresh() = Result.success(listOf(running))
        override suspend fun todos(toolCallId: String) = Result.success(emptyList<SubagentTodo>())
        override suspend fun dismiss(scope: SubagentParentScope, toolCallId: ToolCallId): Result<Unit> {
            dismissed = toolCallId
            return Result.success(Unit)
        }
    }
    private val graph = mockk<SessionGraph> {
        every { subagentRepository } returns repository
        every { id } returns 1L
        every { capturedConfig } returns config
        every { backendDescriptor.backendId } returns BackendId("remote-letta:config")
    }
    private val drawer = androidFocusDrawer(graph).also {
        every { it.sources.sessions.currentGraph } returns MutableStateFlow(graph)
    }

    @After fun tearDown() = drawer.viewModelScope.cancel()

    @Test
    fun viewingARunningSubagentsConversationClosesTheSheetAndOpensItsChat() {
        val shell = openSheet()
        compose.setContent { MaterialTheme { AndroidBackgroundTasks(shell) } }
        compose.onNodeWithTag(SubagentRosterTag.OPEN.of("toolu_run")).performClick()
        compose.runOnIdle {
            assertFalse("the sheet must close once the child's chat opens", shell.workspace.showBackgroundTasks)
            assertEquals(
                CurrentFocus("chat", AgentId(childAgentId), ConversationId("conv-default-$childAgentId"), CurrentFocusChatMode.Exact),
                shell.workspace.currentFocus,
            )
            assertTrue(shell.workspace.page is ShellDestination.Chat)
        }
    }

    @Test
    fun theRunningCardsDismissStillRunsThroughTheSheet() {
        val shell = openSheet()
        compose.setContent { MaterialTheme { AndroidBackgroundTasks(shell) } }
        compose.onNodeWithText("Dismiss").performClick()
        compose.runOnIdle { assertEquals(ToolCallId("toolu_run"), dismissed) }
    }

    private fun openSheet(): WorkspaceShell {
        val workspace = ShellWorkspaceState(ShellDestination.Chat())
        assertTrue(workspace.selectFocus(CurrentFocus("chat", parent, parentConversation, CurrentFocusChatMode.Exact)))
        workspace.showBackgroundTasks = true
        val port = AndroidShellChatPort(workspace, { fleet() }, { listOf(parent) })
        return WorkspaceShell(
            config = config, workspace = workspace, port = port, drawerModel = drawer,
            layout = mockk(relaxed = true), drawer = ShellWorkspaceDrawer(),
            exits = AndroidShellExits({ _, _, _ -> }, {}, SettingsDestinations()),
            chatPanel = mutableStateOf(null), controllers = mockk(relaxed = true),
        )
    }

    /** The parent's conversation is the one loaded; the child's opens without ever being listed. */
    private fun fleet() = listOf(Conversation(id = parentConversation, agentId = parent))
}
