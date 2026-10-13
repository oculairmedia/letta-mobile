package com.letta.mobile.ui.shell.header

import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.search.MessageSearchHit
import com.letta.mobile.ui.search.LettaSearchRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals(UnifiedSearchHit(UnifiedSearchKind.Canvases, "canvas:1"), UnifiedShellSearch.hitOf(LettaSearchRow(id, "")))
        assertNull(UnifiedShellSearch.hitOf(LettaSearchRow("nope", "")))
        assertNull(UnifiedShellSearch.hitOf(LettaSearchRow("unknown:1", "")))
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

    /** letta-mobile-ckmub.3.9: message text joins the results, scoped like conversations, and opens its conversation. */
    @Test
    fun messageHitsAreAResultSectionThatOpensTheirConversation() {
        val hits = listOf(
            MessageSearchHit("c-1", "a-1", "m-1", "Ask Emmanuel about it"),
            MessageSearchHit("c-2", "a-2", "m-2", "emmanuel said hi"),
        )
        val wide = UnifiedShellSearch.sections(UnifiedSearchQuery("emmanuel"), sources, hits)
        val messages = wide.single { it.title == "Messages" }.rows
        assertEquals(listOf("Ask Emmanuel about it", "emmanuel said hi"), messages.map { it.label })
        assertEquals("Plan the release", messages.first().sublabel)
        assertEquals(UnifiedSearchHit(UnifiedSearchKind.Messages, "c-1"), UnifiedShellSearch.hitOf(messages.first()))

        val scoped = UnifiedShellSearch.sections(UnifiedSearchQuery("emmanuel", filterToAgent = true), sources, hits)
        assertEquals(1, scoped.single { it.title == "Messages" }.rows.size)
        assertTrue(UnifiedShellSearch.sections(UnifiedSearchQuery(""), sources, hits).none { it.title == "Messages" })
    }

    /** The header covers canvases, the palette covers destinations; both are the same search. */
    @Test
    fun eachSurfaceCoversItsOwnKinds() {
        val withPages = sources.copy(destinations = listOf(UnifiedSearchDestination("Settings", "Settings")))
        val header = UnifiedShellSearch.sections(UnifiedSearchQuery("settings"), withPages)
        val palette = UnifiedShellSearch.sections(UnifiedSearchQuery("settings", surface = UnifiedSearchSurface.Palette), withPages)
        assertTrue(header.none { it.title == "Go to" })
        assertEquals(listOf("Settings"), palette.single { it.title == "Go to" }.rows.map { it.label })
        assertTrue(UnifiedShellSearch.scopesFor(UnifiedSearchSurface.Palette).none { it.id == UnifiedSearchKind.Canvases.id })
        assertTrue(UnifiedShellSearch.scopes.any { it.id == UnifiedSearchKind.Messages.id })
    }

    @Test
    fun aLongMessageIsShownAroundWhatMatched() {
        val text = "x".repeat(200) + " the deploy finished " + "y".repeat(200)
        val shown = UnifiedShellSearch.snippet(MessageSearchHit("c-1", null, "m-1", text), UnifiedSearchQuery("deploy"))
        assertTrue("deploy" in shown, shown)
        assertTrue(shown.startsWith("…") && shown.endsWith("…"), shown)
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
