package com.letta.mobile.ui.navigation

import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.feature.chat.route.ProjectChatStartAction
import com.letta.mobile.ui.screens.dashboard.AndroidHomeShortcuts
import com.letta.mobile.ui.screens.dashboard.DashboardShortcut
import com.letta.mobile.ui.screens.dashboard.HomeNavigationCallbacks
import com.letta.mobile.ui.screens.dashboard.toDashboardShortcut
import com.letta.mobile.ui.shell.pages.projects.ProjectChatIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** letta-mobile-ckmub.2.5 (S17): where the Admin home's shortcuts and a project's chat lead. */
class AdminNavigationTest {
    private val opened = mutableListOf<String>()

    private val navigation = HomeNavigationCallbacks(
        onNavigateToAgents = { opened += "agents" },
        onNavigateToConversations = { opened += "conversations" },
        onNavigateToTools = { opened += "tools" },
        onNavigateToMemory = { opened += "memory" },
        onNavigateToSettings = { opened += "settings" },
        onNavigateToChat = { agentId, _, _ -> opened += "chat:$agentId" },
        onNavigateToProviders = { opened += "providers" },
        onNavigateToRuns = { opened += "runs" },
        onNavigateToTemplates = { opened += "templates" },
    )

    @Test
    fun blocksOpenMemoryAndModelsOpenProviders() {
        navigation.shortcutNavigator(DashboardShortcut.BLOCKS, favorite = null)()
        navigation.shortcutNavigator(DashboardShortcut.MODELS, favorite = null)()
        navigation.shortcutNavigator(DashboardShortcut.RUNS, favorite = null)()
        navigation.shortcutNavigator(DashboardShortcut.TEMPLATES, favorite = null)()
        assertEquals(listOf("memory", "providers", "runs", "templates"), opened)
    }

    @Test
    fun shortcutsWithNoAppServerRouteAreNotOffered() {
        assertFalse(HomeShortcut.MCP_SERVERS in AndroidHomeShortcuts)
        assertFalse(HomeShortcut.USAGE in AndroidHomeShortcuts)
        assertTrue(HomeShortcut.RUNS in AndroidHomeShortcuts)
        assertNull(HomeShortcut.MCP_SERVERS.toDashboardShortcut())
    }

    @Test
    fun aProjectChatCarriesTheProjectAndTheBugReportStart() {
        val project = ProjectSummary(identifier = "alpha", name = "Alpha", filesystemPath = "/src/alpha", lettaAgentId = AgentId("agent-a"))
        val chat = project.chatRoute(ProjectChatIntent.Chat)
        assertEquals("agent-a", chat.agentId)
        assertEquals("alpha", chat.projectIdentifier)
        assertEquals("/src/alpha", chat.projectFilesystemPath)
        assertNull(chat.projectStartAction)
        assertEquals(ProjectChatStartAction.BUG_REPORT, project.chatRoute(ProjectChatIntent.BugReport).projectStartAction)
    }
}
