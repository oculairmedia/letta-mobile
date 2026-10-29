package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.avatar.core.model.MascotIdentity
import com.letta.mobile.avatar.core.model.MascotShape
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.shell.ShellDrawerConversations
import com.letta.mobile.ui.shell.ShellDrawerLibrary
import com.letta.mobile.ui.shell.rail.collapsedRailEntries
import com.letta.mobile.ui.shell.rail.ShellRailMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The rail lists the agents most recently used first, on every host (the phone's drawer included). */
class ShellWorkspaceRailOrderTest {
    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val roster = listOf("a" to "Ada", "b" to "Bo", "c" to "Cy", "d" to "Di")

    private fun railOrder(library: ShellDrawerLibrary): List<String> =
        ShellWorkspaceState(ShellDestination.Home)
            .workspaceNavigation(roster, now, ShellDrawerConversations(), library)
            .rail.agents.map { it.first }

    @Test
    fun theMostRecentlyActiveAgentsComeFirstAndTheRestKeepRosterOrder() {
        val activity = mapOf(
            "c" to Instant.parse("2026-10-22T11:00:00Z"),
            "b" to Instant.parse("2026-10-20T09:00:00Z"),
        )
        assertEquals(listOf("c", "b", "a", "d"), railOrder(ShellDrawerLibrary(agentLastActiveAt = activity)))
    }

    @Test
    fun pinnedAgentsStayFirst() {
        val activity = mapOf("c" to Instant.parse("2026-10-22T11:00:00Z"))
        val library = ShellDrawerLibrary(agentLastActiveAt = activity, pinnedAgentIds = setOf("d"))
        assertEquals(listOf("d", "c", "a", "b"), railOrder(library))
    }

    @Test
    fun withoutActivityTheRosterOrderStands() {
        assertEquals(listOf("a", "b", "c", "d"), railOrder(ShellDrawerLibrary()))
    }

    @Test
    fun theDrawerOrbsFollowTheRailOrder() {
        val activity = mapOf("d" to Instant.parse("2026-10-22T11:00:00Z"))
        val navigation = ShellWorkspaceState(ShellDestination.Home)
            .workspaceNavigation(roster, now, ShellDrawerConversations(), ShellDrawerLibrary(agentLastActiveAt = activity))
        assertEquals(listOf("Di", "Ada", "Bo", "Cy"), navigation.drawerState().rail.entries.map { it.name })
    }

    @Test
    fun fullRosterAndFocusedPanelSurviveTheCollapsedRecentsCut() {
        val roster = (0 until 100).map { index -> "agent-$index" to "Agent $index" }
        val selectedId = "agent-99"
        val selectedIdentity = MascotIdentity(MascotShape.HEXAGON, argb = 0xFF3FA0F0.toInt())
        val state = ShellWorkspaceState(ShellDestination.Home).apply { focusedAgentId = AgentId(selectedId) }
        val navigation = state.workspaceNavigation(
            agents = roster,
            now = now,
            conversations = ShellDrawerConversations(),
            library = ShellDrawerLibrary(identities = mapOf(selectedId to selectedIdentity)),
        )

        assertEquals(roster, navigation.rail.agents)
        assertEquals(selectedId, navigation.panel.agent.agentId)
        assertEquals(selectedIdentity, navigation.panel.agent.identity)
        assertEquals(selectedId, navigation.rail.focus.selectedAgentId)
        val collapsed = collapsedRailEntries(navigation.rail.agents, navigation.rail.focus)
        assertEquals(8, collapsed.size)
        assertTrue(collapsed.none { it.agentId == selectedId })
        val fullLibrary = ShellRailMapping.entries(
            ShellRailMapping.groups(navigation.rail.agents, selectedAgentId = null),
            navigation.rail.focus,
        )
        assertTrue(fullLibrary.single { it.agentId == selectedId }.selected)
        assertEquals(100, navigation.rail.agents.size)
    }

    @Test
    fun collapsedRecentsUsePinThenActivityOrder() {
        val roster = (0 until 12).map { index -> "agent-$index" to "Agent $index" }
        val activity = mapOf(
            "agent-3" to Instant.parse("2026-10-22T11:00:00Z"),
            "agent-7" to Instant.parse("2026-10-22T10:00:00Z"),
            "agent-5" to Instant.parse("2026-10-22T09:00:00Z"),
        )
        val state = ShellWorkspaceState(ShellDestination.Home)
        val navigation = state.workspaceNavigation(
            agents = roster,
            now = now,
            conversations = ShellDrawerConversations(),
            library = ShellDrawerLibrary(agentLastActiveAt = activity, pinnedAgentIds = setOf("agent-10")),
        )

        assertEquals(
            listOf("agent-10", "agent-3", "agent-7", "agent-5", "agent-0", "agent-1", "agent-2", "agent-4"),
            collapsedRailEntries(navigation.rail.agents, navigation.rail.focus).map { it.agentId },
        )
    }
}
