package com.letta.mobile.desktop

import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.DisplayNames
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "No raw agent ids in user-visible text" is only true if it is true on EVERY
 * surface. These pin the two that were still leaking after the rail was fixed:
 * the rail's own stacking (which merged unrelated agents whose synthetic labels
 * collided) and the Cmd/Ctrl-K palette (whose conversation subtitles read
 * `agentName` straight off the conversation).
 */
class DesktopAgentLabelSurfacesTest {

    private fun conversation(
        id: String,
        agentId: String?,
        agentName: String,
    ) = DesktopConversationSummary(
        id = id,
        title = "Conversation $id",
        agentName = agentName,
        updatedAtLabel = "Just now",
        lastMessagePreview = "preview",
        agentId = agentId,
        archived = false,
    )

    private fun rosterAgent(id: String, name: String) = Agent(id = AgentId(id), name = name)

    @Test
    fun railLabelsNeverRepeatForUnresolvedAgents() {
        // Two roster-only agents whose ids share the first eight characters
        // after the prefix. They used to render the same "Agent 12345678", and
        // the rail stacks by display name — the second was unreachable.
        val agents = buildRailAgents(
            conversations = emptyList(),
            rosterAgents = listOf(
                rosterAgent("agent-12345678-aaaa-1111", ""),
                rosterAgent("agent-12345678-bbbb-2222", ""),
            ),
        )

        assertEquals(2, agents.size)
        assertEquals(2, agents.map { it.second }.distinct().size, "two agents, two distinct rail labels")
        agents.forEach { (_, label) ->
            assertTrue(DisplayNames.isAgentFallback(label), "unresolved agents keep the humane fallback: $label")
        }
    }

    @Test
    fun paletteConversationSubtitlesResolveThroughTheRail() {
        // `agentName` still holds the raw id whenever resolution missed at
        // conversation-load time. The palette used it verbatim, so the one
        // surface a user reaches for when they cannot find something showed
        // them "agent-c356b8f2-1a2b".
        val rawId = "agent-c356b8f2-1a2b"
        val conversations = listOf(conversation("c1", agentId = rawId, agentName = rawId))
        val railAgents = buildRailAgents(conversations, rosterAgents = emptyList())

        val items = buildPaletteItems(conversations, railAgents, workPlayMode = WorkPlayMode.Work)
        val subtitle = items.first { it.id == "c1" }.sublabel

        assertEquals("Agent c356b8f2", subtitle)
        assertFalse(subtitle.orEmpty().contains(rawId), "no raw id may reach the palette")
    }

    @Test
    fun paletteFallsBackForAConversationTheRailDoesNotKnow() {
        val rawId = "agent-deadbeef-0000"
        val conversations = listOf(conversation("c1", agentId = rawId, agentName = rawId))

        val items = buildPaletteItems(conversations, railAgents = emptyList(), workPlayMode = WorkPlayMode.Work)

        assertEquals("Agent deadbeef", items.first { it.id == "c1" }.sublabel)
    }
}
