package com.letta.mobile.ui.navigation

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.shell.workspace.fleetSearchSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Pins what Android's shell searches through the shared fleet sources (ConversationsGraph builds its
 * palette's sources from this very call): the live fleet conversations, the canvas library and the
 * palette's own pages — carried for the focused agent, so a "Go to" row opens its page for it.
 */
@Tag("unit")
class AndroidSearchSourcesTest {
    private val agent = Agent(id = AgentId("agent-1"), name = "Planner")
    private val fleet = listOf(Conversation(id = ConversationId("conv-1"), agentId = agent.id, summary = "Plan the release"))
    private val canvases = listOf(CanvasDocument(id = CanvasId("board-1"), agentId = agent.id.value, title = "Release board"))
    
    /** The palette's sources, exactly as ConversationsGraph builds them for its overlays. */
    private val sources = fleetSearchSources(listOf(agent), fleet, agent.id)
        .copy(canvases = canvases)

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

    /** The palette's own pages ride the focused agent: a "Go to" row opens its page for it. */
    @Test
    fun `the palette's pages are for the focused agent`() {
        assertEquals("memory?agent=agent-1", sources.destinations.first { it.label == "Memory" }.id)
        assertTrue(sources.destinations.none { it.id == "schedules" || it.id == "skills" || it.id == "memory" })
    }
}
