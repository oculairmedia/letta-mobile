package com.letta.mobile.ui.navigation

import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.editagent.EditAgentRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** letta-mobile-ckmub.3.4: Android's typed navigation routes map to and from the shared destinations. */
class AndroidShellRoutesTest {
    private val representable: List<ShellDestination> = listOf(
        ShellDestination.Home,
        ShellDestination.Chat(),
        ShellDestination.Chat(agentId = "agent-1"),
        ShellDestination.Chat(agentId = "agent-1", conversationId = "conv-1"),
        ShellDestination.Canvas(canvasId = "board-1"),
        ShellDestination.Canvas(conversationId = "conv-1", agentId = "agent-1"),
        ShellDestination.Memory(),
        ShellDestination.Memory("agent-1"),
        ShellDestination.Schedules("agent-1"),
        ShellDestination.Channels,
        ShellDestination.Skills(),
        ShellDestination.Skills("agent-1"),
        ShellDestination.Skills(section = ShellSkillsSection.Tools),
        ShellDestination.Settings(),
        ShellDestination.Settings(ShellSettingsSection.Pairing),
        ShellDestination.AgentEditor("agent-1"),
        ShellDestination.Providers,
        ShellDestination.Overview,
    )

    @Test
    fun everyRepresentableDestinationRoundTripsThroughItsAndroidRoute() {
        for (destination in representable) {
            val route = destination.toAndroidRoute()
            assertEquals("via $route", destination, shellDestinationOf(route))
        }
    }

    @Test
    fun theSkillsAndToolsRoutesAreOnePageWithTwoAnchors() {
        assertEquals(SkillsRoute("agent-1"), ShellDestination.Skills("agent-1").toAndroidRoute())
        assertEquals(AllToolsRoute, ShellDestination.Skills(section = ShellSkillsSection.Tools).toAndroidRoute())
        assertEquals(ShellDestination.Skills(section = ShellSkillsSection.Tools), shellDestinationOf(AllToolsRoute))
    }

    @Test
    fun chatAndEditorRoutesCarryTheirIds() {
        assertEquals(
            AgentChatRoute(agentId = "agent-1", conversationId = "conv-1"),
            ShellDestination.Chat("agent-1", "conv-1").toAndroidRoute(),
        )
        assertEquals(EditAgentRoute("agent-1"), ShellDestination.AgentEditor("agent-1").toAndroidRoute())
        // A chat route opened from a notification keeps its target conversation.
        assertEquals(
            ShellDestination.Chat("agent-1", "conv-9"),
            shellDestinationOf(AgentChatRoute(agentId = "agent-1", conversationId = "conv-9", scrollToMessageId = "m-1")),
        )
    }

    @Test
    fun whatAndroidRoutesCannotCarryIsDocumentedNotInvented() {
        // A chat needs its agent on Android: without one the conversation list opens.
        assertEquals(ConversationsRoute, ShellDestination.Chat(conversationId = "conv-1").toAndroidRoute())
        // The standalone Tools route has no agent.
        assertEquals(AllToolsRoute, ShellDestination.Skills("agent-1", ShellSkillsSection.Tools).toAndroidRoute())
    }

    @Test
    fun routesOutsideTheSharedShellHaveNoDestination() {
        assertNull(shellDestinationOf(RunsRoute))
        assertNull(shellDestinationOf(PairingScanRoute))
        assertNull(shellDestinationOf(ArchivalRoute("agent-1")))
        assertNull(shellDestinationOf(HomeRoute))
    }
}
