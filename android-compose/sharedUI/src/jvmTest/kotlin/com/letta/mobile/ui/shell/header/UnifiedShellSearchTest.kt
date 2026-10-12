package com.letta.mobile.ui.shell.header

import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnifiedShellSearchTest {
    private val sources = UnifiedSearchSources(
        conversations = listOf(
            conversation("c-1", "Plan the release", agentId = "a-1"),
            conversation("c-2", "Plan dinner", agentId = "a-2"),
        ),
        agents = listOf(Agent(id = AgentId("a-1"), name = "Planner"), Agent(id = AgentId("a-2"), name = "Writer")),
        agentId = "a-1",
    )

    @Test
    fun rowIdsRoundTripToTheirKind() {
        val id = UnifiedShellSearch.rowId(UnifiedSearchKind.Canvases, "canvas:1")
        assertEquals(UnifiedSearchHit(UnifiedSearchKind.Canvases, "canvas:1"), UnifiedShellSearch.parseRowId(id))
        assertNull(UnifiedShellSearch.parseRowId("nope"))
        assertNull(UnifiedShellSearch.parseRowId("unknown:1"))
    }

    @Test
    fun filterToAgentNarrowsConversationsButNotAgents() {
        val sections = UnifiedShellSearch.sections(UnifiedSearchQuery("plan", filterToAgent = true), sources)
        assertEquals(listOf("Plan the release"), sections.single { it.title == "Conversations" }.rows.map { it.label })
        assertEquals(listOf("Planner"), sections.single { it.title == "Agents" }.rows.map { it.label })
    }

    @Test
    fun widenedSearchAndScopes() {
        val all = UnifiedShellSearch.sections(UnifiedSearchQuery("plan", filterToAgent = false), sources)
        assertEquals(2, all.single { it.title == "Conversations" }.rows.size)
        val agentsOnly = UnifiedShellSearch.sections(UnifiedSearchQuery("", filterToAgent = false, scopeId = UnifiedSearchKind.Agents.id), sources)
        assertEquals(listOf("Agents"), agentsOnly.map { it.title })
    }

    private fun conversation(id: String, title: String, agentId: String) = ChatConversationSummary(
        id = id,
        title = title,
        agentName = agentId,
        updatedAtLabel = "2026-10-01T00:00:00Z",
        lastMessagePreview = "",
        agentId = agentId,
    )
}
