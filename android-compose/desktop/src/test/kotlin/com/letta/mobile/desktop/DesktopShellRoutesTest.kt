package com.letta.mobile.desktop

import androidx.compose.runtime.mutableStateOf
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.data.shell.ShellSkillsSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-ckmub.3.4: the desktop route model maps to and from the shared destinations. */
class DesktopShellRoutesTest {
    private val destinations: List<ShellDestination> = listOf(
        ShellDestination.Home,
        ShellDestination.Chat(),
        ShellDestination.Chat(agentId = "agent-1"),
        ShellDestination.Chat(agentId = "agent-1", conversationId = "conv-1"),
        ShellDestination.Canvas(canvasId = "board-1"),
        ShellDestination.Canvas(conversationId = "conv-1", agentId = "agent-1"),
        ShellDestination.Memory("agent-1"),
        ShellDestination.Schedules("agent-1"),
        ShellDestination.Channels,
        ShellDestination.Skills("agent-1"),
        ShellDestination.Skills(section = ShellSkillsSection.Tools),
        ShellDestination.Settings(),
        ShellDestination.Settings(ShellSettingsSection.Pairing),
        ShellDestination.AgentEditor("agent-1"),
        ShellDestination.AgentEditor(),
        ShellDestination.Providers,
        ShellDestination.Overview,
        ShellDestination.Runs,
        ShellDestination.Templates,
        ShellDestination.Projects,
    )

    @Test
    fun everySharedDestinationRoundTripsThroughTheDesktopRoute() {
        for (destination in destinations) {
            assertEquals(destination, destination.toDesktopRoute().toShellDestination(), "via ${destination.toDesktopRoute()}")
        }
    }

    @Test
    fun everyDesktopDestinationHasASharedDestination() {
        for (desktop in DesktopDestination.entries) {
            val shared = DesktopShellRoute(desktop).toShellDestination()
            assertEquals(desktop, shared.toDesktopRoute().destination, "$desktop -> $shared")
        }
    }

    @Test
    fun theEditorAndCanvasArePanelsBesideTheChatPage() {
        val editing = ShellDestination.AgentEditor("agent-1").toDesktopRoute()
        assertEquals(DesktopDestination.Conversations, editing.destination)
        assertEquals("agent-1", editing.editAgentId)
        assertEquals(ShellDestination.Chat(agentId = "agent-1"), editing.page.toShellDestination())
        val canvas = ShellDestination.Canvas(canvasId = "board-1", conversationId = "conv-1").toDesktopRoute()
        assertEquals(ShellDestination.Chat(conversationId = "conv-1"), canvas.page.toShellDestination())
    }

    @Test
    fun theNavigatorShowsTheRouteOfASharedDestination() {
        val navigator = DesktopShellNavigator(mutableStateOf(DesktopDestination.Home), mutableStateOf(false))
        navigator.navigate(ShellDestination.Skills(section = ShellSkillsSection.Tools))
        assertEquals(DesktopDestination.Agents, navigator.selectedDestination)
        assertEquals(
            ShellDestination.Skills("agent-1", ShellSkillsSection.Tools),
            navigator.currentRoute(selectedConversationId = null, focusedAgentId = "agent-1").toShellDestination(),
        )
        navigator.navigate(ShellDestination.AgentEditor("agent-2"))
        assertEquals("agent-2", navigator.editAgentId)
        // The rail's own navigation leaves the editor and resets the anchors.
        navigator.navigate(DesktopDestination.Agents)
        assertNull(navigator.editAgentId)
        assertEquals(ShellSkillsSection.Skills, navigator.skillsSection)
        navigator.navigate(ShellDestination.Settings(ShellSettingsSection.Pairing))
        assertEquals(ShellSettingsSection.Pairing, navigator.settingsSection)
    }

    @Test
    fun theNewAgentFlowIsTheEditorPanelWithoutAnAgent() {
        val navigator = DesktopShellNavigator(mutableStateOf(DesktopDestination.Home), mutableStateOf(false))
        navigator.openNewAgent()
        assertEquals(DesktopDestination.Conversations, navigator.selectedDestination)
        assertEquals(ShellDestination.AgentEditor(), navigator.agentEditor)
        assertNull(navigator.editAgentId)
        assertEquals(ShellDestination.AgentEditor(), navigator.currentRoute(null, "agent-1").toShellDestination())
        // Closing any editor closes the new-agent flow too.
        navigator.editAgentId = null
        assertNull(navigator.agentEditor)
    }
}
