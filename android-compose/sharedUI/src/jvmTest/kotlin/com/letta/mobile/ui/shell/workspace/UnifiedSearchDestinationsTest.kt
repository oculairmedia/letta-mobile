package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.shell.SessionShellChatPort
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.shell.header.UnifiedSearchHit
import com.letta.mobile.ui.shell.header.UnifiedSearchKind
import com.letta.mobile.ui.shell.header.UnifiedSearchQuery
import com.letta.mobile.ui.shell.header.UnifiedSearchSurface
import com.letta.mobile.ui.shell.header.UnifiedShellSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The palette's "Go to" rows on a fleet host: each carries the focused agent, so a pick opens its
 * page for the agent the row was built for — the shell's focus follows the row, never silently
 * keeping another agent's focus. (The shell's route model keeps the agent in the focus, so the
 * memory page binds it there: memoryForFocus(memory, currentFocus.agentId).)
 */
class UnifiedSearchDestinationsTest {
    private val agent = AgentId("agent-1")

    @Test
    fun pickingAGoToRowOpensItsPageForTheAgentItWasBuiltFor() {
        val workspace = ShellWorkspaceState(ShellDestination.Chat(agent.value))
        val navigator = ShellWorkspaceNavigator(workspace, SessionShellChatPort())
        val sources = fleetSearchSources(emptyList(), emptyList(), agent)
        val paletteRows = UnifiedShellSearch.sections(UnifiedSearchQuery("memory", surface = UnifiedSearchSurface.Palette), sources)
        val memory = paletteRows.single { it.title == "Go to" }.rows.single { it.label == "Memory" }

        // The person re-focuses another agent before picking the row the palette built for agent-1.
        navigator.openAgent(AgentId("agent-2"))
        assertEquals(AgentId("agent-2"), workspace.currentFocus.agentId)

        navigator.openSearchHit(UnifiedShellSearch.hitOf(memory)!!)

        assertEquals(ShellDestination.Memory::class, workspace.page::class)
        assertEquals(agent, workspace.currentFocus.agentId)
    }

    /** Each id carries the focused agent — never another agent's — or nothing, when none is focused. */
    @Test
    fun goToRowsCarryTheFocusedAgent() {
        val focused = fleetSearchSources(emptyList(), emptyList(), agent).destinations
        val unfocused = fleetSearchSources(emptyList(), emptyList(), null).destinations

        assertEquals(listOf("memory?agent=agent-1"), focused.filter { it.label == "Memory" }.map { it.id })
        assertTrue(unfocused.filter { it.label == "Memory" }.all { it.id == "memory" })

        val pick = focused.first { it.label == "Memory" }.id
        assertEquals(
            UnifiedSearchHit(UnifiedSearchKind.Destinations, pick),
            UnifiedShellSearch.hitOf(LettaSearchRow(UnifiedShellSearch.rowId(UnifiedSearchKind.Destinations, pick), "")),
        )
    }
}
