package com.letta.mobile.ui.screens.agentlist

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentListDisplayAgentsTest {

    @Test
    fun `matching favorite remains visible during search`() {
        val meridian = Agent(id = AgentId("meridian"), name = "Meridian")
        val other = Agent(id = AgentId("other"), name = "Other")

        val display = resolveAgentListDisplayAgents(
            filteredAgents = listOf(meridian, other),
            favoriteAgent = meridian,
        )

        assertEquals(meridian, display.visibleFavoriteAgent)
        assertEquals(listOf(other), display.listAgents)
    }

    @Test
    fun `non matching favorite is not removed from search results`() {
        val meridian = Agent(id = AgentId("meridian"), name = "Meridian")
        val favorite = Agent(id = AgentId("favorite"), name = "Favorite")

        val display = resolveAgentListDisplayAgents(
            filteredAgents = listOf(meridian),
            favoriteAgent = favorite,
        )

        assertNull(display.visibleFavoriteAgent)
        assertEquals(listOf(meridian), display.listAgents)
    }

    @Test
    fun `pinned agents are grouped before regular agents without moving favorite duplicate`() {
        val favorite = Agent(id = AgentId("favorite"), name = "Favorite")
        val pinned = Agent(id = AgentId("pinned"), name = "Pinned")
        val regular = Agent(id = AgentId("regular"), name = "Regular")

        val display = resolveAgentListDisplayAgents(
            filteredAgents = listOf(regular, favorite, pinned),
            favoriteAgent = favorite,
            pinnedAgentIds = setOf(AgentId("pinned")),
        )

        assertEquals(favorite, display.visibleFavoriteAgent)
        assertEquals(listOf(pinned, regular), display.listAgents)
    }
}
