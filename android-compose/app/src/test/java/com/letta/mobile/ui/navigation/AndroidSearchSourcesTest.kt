package com.letta.mobile.ui.navigation

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.settings.defaultUnifiedSearchOptions
import com.letta.mobile.ui.shell.workspace.fleetSearchSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Pins what Android's command palette searches (ConversationsGraph wires its overlay's sources
 * from these very calls): the live fleet conversations, the canvas library, the palette's own
 * pages and the Settings options — not only the agents, as the older palette did.
 */
@Tag("unit")
class AndroidSearchSourcesTest {
    private val agent = Agent(id = AgentId("agent-1"), name = "Planner")
    private val fleet = listOf(Conversation(id = ConversationId("conv-1"), agentId = agent.id, summary = "Plan the release"))
    private val canvases = listOf(CanvasDocument(id = CanvasId("board-1"), agentId = agent.id.value, title = "Release board"))
    private val config = LettaConfig(id = "config", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://letta.example")

    /** The palette's sources, exactly as ConversationsGraph builds them for its overlays. */
    private val sources = fleetSearchSources(agents = listOf(agent), fleet = fleet, focusedAgentId = agent.id, canvases = canvases)
        .copy(settings = defaultUnifiedSearchOptions(config, notifications = true, providers = true))

    @Test
    fun `the palette searches live conversations`() {
        assertEquals(listOf("conv-1"), sources.conversations.map { it.id })
    }

    @Test
    fun `the palette searches agents and canvases`() {
        assertEquals(listOf("agent-1"), sources.agents.map { it.id.value })
        assertEquals("agent-1", sources.agentId)
        assertEquals(listOf("board-1"), sources.canvases.map { it.id.value })
    }

    @Test
    fun `the palette carries settings entries`() {
        assertTrue(sources.settings.any { it.group == "Appearance" })
        assertTrue(sources.settings.any { it.group == "Notifications" })
        assertTrue(sources.settings.any { it.group == "Providers" })
    }

    @Test
    fun `the palette carries its destinations`() {
        assertTrue(sources.destinations.isNotEmpty())
    }
}
