package com.letta.mobile.ui.screens.agentlist

import com.letta.mobile.data.model.Agent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentListDisplayAgentsTest {

    @Test
    fun `matching favorite remains visible during search`() {
        val meridian = Agent(id = "meridian", name = "Meridian")
        val other = Agent(id = "other", name = "Other")

        val display = resolveAgentListDisplayAgents(
            filteredAgents = listOf(meridian, other),
            favoriteAgent = meridian,
        )

        assertEquals(meridian, display.visibleFavoriteAgent)
        assertEquals(listOf(other), display.listAgents)
    }

    @Test
    fun `non matching favorite is not removed from search results`() {
        val meridian = Agent(id = "meridian", name = "Meridian")
        val favorite = Agent(id = "favorite", name = "Favorite")

        val display = resolveAgentListDisplayAgents(
            filteredAgents = listOf(meridian),
            favoriteAgent = favorite,
        )

        assertNull(display.visibleFavoriteAgent)
        assertEquals(listOf(meridian), display.listAgents)
    }
}
