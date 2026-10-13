package com.letta.mobile.desktop

import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.DisplayNames
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.ui.search.LettaSearchLeading
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.shell.header.UnifiedSearchKind
import com.letta.mobile.ui.shell.header.UnifiedSearchQuery
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.header.UnifiedSearchSurface
import com.letta.mobile.ui.shell.header.UnifiedShellSearch
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

    /** The command palette's rows: the unified search on the palette surface (letta-mobile-ckmub.3.9). */
    private fun paletteRows(conversations: List<DesktopConversationSummary>, roster: List<Agent> = emptyList()): List<LettaSearchRow> =
        UnifiedShellSearch.sections(
            UnifiedSearchQuery("", surface = UnifiedSearchSurface.Palette),
            UnifiedSearchSources(conversations = conversations, agents = roster),
        ).flatMap { it.rows }

    private fun List<LettaSearchRow>.row(kind: UnifiedSearchKind, id: String): LettaSearchRow =
        first { it.id == UnifiedShellSearch.rowId(kind, id) }

    private fun LettaSearchRow.orbAgentId(): String? = (leading as? LettaSearchLeading.Orb)?.agentId

    @Test
    fun paletteConversationSubtitlesNeverShowARawId() {
        // `agentName` still holds the raw id whenever resolution missed at
        // conversation-load time. The palette used it verbatim, so the one
        // surface a user reaches for when they cannot find something showed
        // them "agent-c356b8f2-1a2b".
        val rawId = "agent-c356b8f2-1a2b"
        val rows = paletteRows(listOf(conversation("c1", agentId = rawId, agentName = rawId)))
        val subtitle = rows.row(UnifiedSearchKind.Conversations, "c1").sublabel

        assertEquals("Agent c356b8f2", subtitle)
        assertFalse(subtitle.orEmpty().contains(rawId), "no raw id may reach the palette")
    }

    @Test
    fun paletteConversationSubtitlesUseTheRostersName() {
        val rows = paletteRows(
            listOf(conversation("c1", agentId = "agent-deadbeef-0000", agentName = "agent-deadbeef-0000")),
            roster = listOf(rosterAgent("agent-deadbeef-0000", "Meridian")),
        )

        assertEquals("Meridian", rows.row(UnifiedSearchKind.Conversations, "c1").sublabel)
    }

    @Test
    fun paletteRowsCarryTheOwningAgentForMascots() {
        val rows = paletteRows(
            listOf(conversation("c1", agentId = "agent-owner", agentName = "Owner")),
            roster = listOf(rosterAgent("agent-owner", "Owner"), rosterAgent("agent-other", "Other")),
        )

        assertEquals("agent-owner", rows.row(UnifiedSearchKind.Conversations, "c1").orbAgentId())
        assertEquals("agent-other", rows.row(UnifiedSearchKind.Agents, "agent-other").orbAgentId())
    }

    @Test
    fun paletteConversationWithoutOwningAgentHasNoAvatar() {
        val rows = paletteRows(listOf(conversation("c1", agentId = null, agentName = "Unknown")))
        assertEquals(LettaSearchLeading.None, rows.row(UnifiedSearchKind.Conversations, "c1").leading)
    }

    @Test
    fun paletteDestinationsAreListedOnce() {
        val destinations = buildPaletteDestinations(WorkPlayMode.Work)
        assertTrue(destinations.isNotEmpty())
        assertEquals(destinations.size, destinations.map { it.id }.distinct().size)
    }
}
