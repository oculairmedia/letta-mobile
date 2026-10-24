package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.data.agents.RecentAgentsPolicy
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.shell.ShellDrawerConversations
import com.letta.mobile.ui.shell.ShellDrawerLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun capsLargeRostersToRecentLimitWhileKeepingFocusedAndPinnedAgents() {
        val largeRoster = (1..20).map { "a$it" to "Agent $it" }
        val activity = mapOf("a15" to Instant.parse("2026-10-22T11:00:00Z"))
        val library = ShellDrawerLibrary(agentLastActiveAt = activity, pinnedAgentIds = setOf("a12"))
        val state = ShellWorkspaceState(ShellDestination.Chat(AgentId("a20")))
        val railIds = state.workspaceNavigation(largeRoster, now, ShellDrawerConversations(), library)
            .rail.agents.map { it.first }
        // 1 pinned ("a12") + DEFAULT_MAX_AGENTS (8) unpinned recents/top-up + 1 focused ("a20") = 10
        assertEquals(RecentAgentsPolicy.DEFAULT_MAX_AGENTS + 2, railIds.size)
        assertEquals("a12", railIds.first())
        assertEquals("a15", railIds[1])
        assertEquals("a20", railIds.last())
    }
}
