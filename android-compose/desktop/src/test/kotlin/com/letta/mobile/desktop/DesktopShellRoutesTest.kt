package com.letta.mobile.desktop

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.data.shell.ShellWorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-ckmub.3.4 / 17.3: the desktop's destinations are a view of the shared workspace's page;
 * the state itself is covered by the shared ShellWorkspaceStateTest.
 */
class DesktopShellRoutesTest {
    private val destinations: List<ShellDestination> = listOf(
        ShellDestination.Home,
        ShellDestination.Chat(agentId = "agent-1", conversationId = "conv-1"),
        ShellDestination.Canvas(canvasId = "board-1"),
        ShellDestination.Memory("agent-1"),
        ShellDestination.Schedules("agent-1"),
        ShellDestination.Channels,
        ShellDestination.Skills(section = ShellSkillsSection.Tools),
        ShellDestination.Settings(ShellSettingsSection.Pairing),
        ShellDestination.AgentEditor("agent-1"),
        ShellDestination.Providers,
        ShellDestination.Overview,
        ShellDestination.Runs,
        ShellDestination.Templates,
        ShellDestination.Projects,
        ShellDestination.Plugins,
    )

    @Test
    fun everyDesktopDestinationIsASharedPageAndBack() {
        for (desktop in DesktopDestination.entries) {
            assertEquals(desktop, desktop.shellPage.desktopDestination, "$desktop -> ${desktop.shellPage}")
        }
    }

    @Test
    fun everySharedDestinationShowsTheDesktopDestinationOfItsPage() {
        for (destination in destinations) {
            val workspace = ShellWorkspaceState()
            workspace.navigate(destination)
            assertEquals(destination.desktopDestination, workspace.selectedDestination, "$destination")
        }
        assertEquals(DesktopDestination.Conversations, ShellDestination.AgentEditor().desktopDestination)
    }

    @Test
    fun theRailsNavigationLeavesTheEditorAndResetsTheAnchors() {
        val workspace = ShellWorkspaceState()
        workspace.navigate(ShellDestination.Skills(section = ShellSkillsSection.Tools))
        assertEquals(DesktopDestination.Agents, workspace.selectedDestination)
        assertEquals(
            ShellDestination.Skills("agent-1", ShellSkillsSection.Tools),
            workspace.pageFor(AgentId("agent-1"), null),
        )
        workspace.navigate(ShellDestination.AgentEditor("agent-2"))
        assertEquals(AgentId("agent-2"), workspace.editAgentId)
        assertEquals(DesktopDestination.Conversations, workspace.selectedDestination)

        workspace.navigate(DesktopDestination.Agents)
        assertNull(workspace.editAgentId)
        assertEquals(ShellDestination.Skills(), workspace.page)
    }

    @Test
    fun selectingADestinationKeepsTheEditorBesideTheChat() {
        val workspace = ShellWorkspaceState()
        workspace.openNewAgent()
        workspace.selectedDestination = DesktopDestination.Conversations
        assertEquals(ShellDestination.AgentEditor(), workspace.agentEditor)
        workspace.selectedDestination = DesktopDestination.Home
        assertEquals(ShellDestination.Home, workspace.page)
    }
}
