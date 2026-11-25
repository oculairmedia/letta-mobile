package com.letta.mobile.ui.navigation

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.screens.config.androidSettingsCatalog
import com.letta.mobile.ui.shell.workspace.fleetSearchSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Pins what Android's shell searches, header and palette alike (AdaptiveScaffold and
 * ConversationsGraph wire their sources from these very calls): the live fleet conversations, the
 * canvas library, the palette's own pages and the Settings catalog — where the older palette fed
 * the search agents only, and the header fed it no Settings options at all.
 */
@Tag("unit")
class AndroidSearchSourcesTest {
    private val agent = Agent(id = AgentId("agent-1"), name = "Planner")
    private val fleet = listOf(Conversation(id = ConversationId("conv-1"), agentId = agent.id, summary = "Plan the release"))
    private val canvases = listOf(CanvasDocument(id = CanvasId("board-1"), agentId = agent.id.value, title = "Release board"))
    private val config = LettaConfig(id = "config", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://letta.example")

    /** The header's sources, exactly as AdaptiveScaffold builds them: nothing but the Settings options added. */
    private val header = fleetSearchSources(listOf(agent), fleet, agent.id)
        .copy(settings = androidSettingsCatalog(config))

    /** The palette's sources, exactly as ConversationsGraph builds them for its overlays. */
    private val palette = fleetSearchSources(listOf(agent), fleet, agent.id)
        .copy(canvases = canvases, settings = androidSettingsCatalog(config))

    @Test
    fun `the header searches settings options too`() {
        assertTrue(header.settings.isNotEmpty())
        assertTrue(header.settings.any { it.group == "Notifications" })
        assertTrue(header.settings.any { it.group == "Providers" })
    }

    @Test
    fun `the palette searches live conversations`() {
        assertEquals(listOf("conv-1"), palette.conversations.map { it.id })
    }

    @Test
    fun `the palette searches agents and canvases`() {
        assertEquals(listOf("agent-1"), palette.agents.map { it.id.value })
        assertEquals("agent-1", palette.agentId)
        assertEquals(listOf("board-1"), palette.canvases.map { it.id.value })
    }

    @Test
    fun `the palette carries its destinations`() {
        assertTrue(palette.destinations.isNotEmpty())
    }
}
