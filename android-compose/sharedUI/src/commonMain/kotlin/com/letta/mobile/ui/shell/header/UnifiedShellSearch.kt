package com.letta.mobile.ui.shell.header

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.home.conversationRecency
import com.letta.mobile.data.home.relativeAgeLabel
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.search.TextMatch
import com.letta.mobile.ui.search.LettaSearchLeading
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.LettaSearchScope
import com.letta.mobile.ui.search.LettaSearchSection

/** What the unified shell search looks through. Hosts feed what their graph has; empty lists are fine. */
data class UnifiedSearchSources(
    val conversations: List<ChatConversationSummary> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val canvases: List<CanvasDocument> = emptyList(),
    val avatarStyleByAgentId: Map<String, Int> = emptyMap(),
    /** The focused agent: "Filter to this agent" narrows to it. */
    val agentId: String? = null,
)

/** The kind of thing a unified search row opens. */
enum class UnifiedSearchKind(val id: String, val label: String) {
    Conversations("conversations", "Conversations"),
    Agents("agents", "Agents"),
    Canvases("canvases", "Canvases"),
}

/**
 * What the user asked the unified search: the typed [text], the "Filter to this agent" checkbox
 * (on by default) and the scope tab ([UnifiedShellSearch.ALL] or a [UnifiedSearchKind.id]).
 */
data class UnifiedSearchQuery(
    val text: String,
    val filterToAgent: Boolean = true,
    val scopeId: String = UnifiedShellSearch.ALL,
) {
    fun includes(kind: UnifiedSearchKind): Boolean = scopeId == UnifiedShellSearch.ALL || scopeId == kind.id
}

/** A picked row: what kind it is and the id of the thing to open. */
data class UnifiedSearchHit(val kind: UnifiedSearchKind, val id: String)

/**
 * The shell header search's data (was desktop's DesktopUnifiedSearch): one query across
 * conversations, agents and canvases. Matching is [TextMatch]'s, the same token-based primitive the
 * command palette, the mention catalog and the model picker use. The UI half is `LettaSearch`.
 */
object UnifiedShellSearch {
    /** The default scope; the others narrow to one [UnifiedSearchKind]. */
    const val ALL = "all"

    val scopes: List<LettaSearchScope> =
        listOf(LettaSearchScope(ALL, "All")) + UnifiedSearchKind.entries.map { LettaSearchScope(it.id, it.label) }

    /** Row ids carry their kind so a pick routes without a parallel lookup; [parseRowId] reads them. */
    fun rowId(kind: UnifiedSearchKind, id: String): String = "${kind.id}:$id"

    /** The hit behind "conversations:conv-1"; null if malformed or of an unknown kind. */
    fun parseRowId(rowId: String): UnifiedSearchHit? {
        val separator = rowId.indexOf(':')
        if (separator <= 0 || separator == rowId.lastIndex) return null
        val kind = UnifiedSearchKind.entries.firstOrNull { it.id == rowId.substring(0, separator) } ?: return null
        return UnifiedSearchHit(kind, rowId.substring(separator + 1))
    }

    /**
     * The sections for [query] over [sources]. [UnifiedSearchQuery.filterToAgent] restricts
     * conversations and canvases to the focused agent; agents are never restricted, since narrowing
     * the agent list to the agent already selected would leave one row.
     */
    fun sections(
        query: UnifiedSearchQuery,
        sources: UnifiedSearchSources,
        limitPerSection: Int = DEFAULT_LIMIT,
    ): List<LettaSearchSection> {
        val scopedAgentId = sources.agentId.takeIf { query.filterToAgent }
        val search = SectionSearch(query.text, sources, scopedAgentId, limitPerSection)
        val inScope = query::includes
        return listOf(
            LettaSearchSection("Conversations", if (inScope(UnifiedSearchKind.Conversations)) search.conversations() else emptyList()),
            LettaSearchSection("Agents", if (inScope(UnifiedSearchKind.Agents)) search.agents() else emptyList()),
            LettaSearchSection("Canvases", if (inScope(UnifiedSearchKind.Canvases)) search.canvases() else emptyList()),
        ).filter { it.rows.isNotEmpty() }
    }

    private class SectionSearch(
        val query: String,
        val sources: UnifiedSearchSources,
        val scopedAgentId: String?,
        val limit: Int,
    ) {
        fun conversations(): List<LettaSearchRow> = sources.conversations
            .filter { scopedAgentId == null || it.agentId == scopedAgentId }
            .filter { TextMatch.matches(query, it.displayTitle(), it.agentName) }
            .sortedByDescending { conversationRecency(it.updatedAtLabel) }
            .take(limit)
            .map { conversation ->
                LettaSearchRow(
                    id = rowId(UnifiedSearchKind.Conversations, conversation.id),
                    label = conversation.displayTitle(),
                    // Scoped to one agent, every row would repeat the agent name you already gave.
                    sublabel = conversation.agentName.takeIf { scopedAgentId == null },
                    meta = relativeAgeLabel(conversation.updatedAtLabel),
                    leading = orbFor(conversation.agentId, conversation.agentName),
                )
            }

        fun agents(): List<LettaSearchRow> = sources.agents
            .filter { TextMatch.matches(query, it.name, it.description, it.model) }
            .take(limit)
            .map { agent ->
                LettaSearchRow(
                    id = rowId(UnifiedSearchKind.Agents, agent.id.value),
                    label = agent.name,
                    sublabel = agent.description?.takeIf { it.isNotBlank() } ?: agent.model,
                    leading = orbFor(agent.id.value, agent.name),
                )
            }

        fun canvases(): List<LettaSearchRow> = sources.canvases
            .filter { scopedAgentId == null || it.agentId == null || it.agentId == scopedAgentId }
            .filter { TextMatch.matches(query, it.title) }
            .sortedByDescending { it.updatedAtEpochMs }
            .take(limit)
            .map { canvas ->
                LettaSearchRow(
                    id = rowId(UnifiedSearchKind.Canvases, canvas.id.value),
                    label = canvas.title,
                    sublabel = "Canvas",
                    leading = orbFor(canvas.agentId),
                )
            }

        /** The owning agent's avatar (mascot or initial tile); a row with no owning agent gets none. */
        private fun orbFor(agentId: String?, agentName: String? = null): LettaSearchLeading = agentId
            ?.let { id ->
                val name = agentName?.takeIf { it.isNotBlank() } ?: sources.agents.firstOrNull { it.id.value == id }?.name
                LettaSearchLeading.Orb(orbIndex = sources.avatarStyleByAgentId[id] ?: 0, agentId = id, name = name)
            }
            ?: LettaSearchLeading.None
    }

    private const val DEFAULT_LIMIT = 20
}
