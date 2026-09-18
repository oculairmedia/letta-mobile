package com.letta.mobile.desktop

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.search.TextMatch
import com.letta.mobile.ui.search.LettaSearchLeading
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.LettaSearchScope
import com.letta.mobile.ui.search.LettaSearchSection

/**
 * The header search's data: one query across conversations, agents and canvases.
 *
 * This is the data half of the unified search; the UI half is `LettaSearch` in
 * sharedUI, which renders it. Matching is [TextMatch]'s — the same token-based
 * primitive the command palette, the mention catalog and the model picker use,
 * so "pm letta mobile" finds "PM - letta-mobile" here exactly as it does
 * everywhere else.
 */
object DesktopUnifiedSearch {

    /** Scope ids. [ALL] is the default; the rest narrow to one kind. */
    const val ALL = "all"
    const val CONVERSATIONS = "conversations"
    const val AGENTS = "agents"
    const val CANVASES = "canvases"

    val scopes: List<LettaSearchScope> = listOf(
        LettaSearchScope(ALL, "All"),
        LettaSearchScope(CONVERSATIONS, "Conversations"),
        LettaSearchScope(AGENTS, "Agents"),
        LettaSearchScope(CANVASES, "Canvases"),
    )

    /**
     * Row ids carry their kind so a selection can be routed without the UI
     * holding a parallel lookup table. [parseRowId] is the only reader.
     */
    fun rowId(kind: String, id: String): String = "$kind:$id"

    /** ("conversations", "conv-1") from "conversations:conv-1"; null if malformed. */
    fun parseRowId(rowId: String): Pair<String, String>? {
        val separator = rowId.indexOf(':')
        if (separator <= 0 || separator == rowId.lastIndex) return null
        return rowId.substring(0, separator) to rowId.substring(separator + 1)
    }

    /**
     * Build the sections for [query].
     *
     * [filterToAgent] is the "Filter to this agent" checkbox. On (the default)
     * it restricts conversations and canvases to [agentId], which is the
     * existing constrained behaviour; off widens to everything. Agents are
     * never restricted — narrowing the agent list to the agent you already
     * have selected would leave exactly one row.
     */
    fun sections(
        query: String,
        conversations: List<ChatConversationSummary>,
        agents: List<Agent>,
        canvases: List<CanvasDocument>,
        avatarStyleByAgentId: Map<String, Int>,
        agentId: String?,
        filterToAgent: Boolean,
        scopeId: String = ALL,
        limitPerSection: Int = DEFAULT_LIMIT,
    ): List<LettaSearchSection> {
        val scopedAgentId = agentId.takeIf { filterToAgent }

        val conversationRows = if (scopeId == ALL || scopeId == CONVERSATIONS) {
            conversations
                .filter { scopedAgentId == null || it.agentId == scopedAgentId }
                .filter { TextMatch.matches(query, it.displayTitle(), it.agentName) }
                .sortedByDescending { conversationRecency(it.updatedAtLabel) }
                .take(limitPerSection)
                .map { conversation ->
                    LettaSearchRow(
                        id = rowId(CONVERSATIONS, conversation.id),
                        label = conversation.displayTitle(),
                        sublabel = conversation.agentName,
                        meta = conversation.updatedAtLabel,
                        leading = orbFor(conversation.agentId, avatarStyleByAgentId),
                    )
                }
        } else {
            emptyList()
        }

        val agentRows = if (scopeId == ALL || scopeId == AGENTS) {
            agents
                .filter { TextMatch.matches(query, it.name, it.description, it.model) }
                .take(limitPerSection)
                .map { agent ->
                    LettaSearchRow(
                        id = rowId(AGENTS, agent.id.value),
                        label = agent.name,
                        sublabel = agent.description?.takeIf { it.isNotBlank() } ?: agent.model,
                        leading = orbFor(agent.id.value, avatarStyleByAgentId),
                    )
                }
        } else {
            emptyList()
        }

        val canvasRows = if (scopeId == ALL || scopeId == CANVASES) {
            canvases
                .filter { scopedAgentId == null || it.agentId == null || it.agentId == scopedAgentId }
                .filter { TextMatch.matches(query, it.title) }
                .sortedByDescending { it.updatedAtEpochMs }
                .take(limitPerSection)
                .map { canvas ->
                    LettaSearchRow(
                        id = rowId(CANVASES, canvas.id.value),
                        label = canvas.title,
                        sublabel = "Canvas",
                        leading = orbFor(canvas.agentId, avatarStyleByAgentId),
                    )
                }
        } else {
            emptyList()
        }

        return listOf(
            LettaSearchSection("Conversations", conversationRows),
            LettaSearchSection("Agents", agentRows),
            LettaSearchSection("Canvases", canvasRows),
        ).filter { it.rows.isNotEmpty() }
    }

    /**
     * The agent's orb, so a result shows the live mascot exactly as the rail
     * does. A row with no owning agent (an unassigned canvas) gets no leading
     * element rather than a meaningless orb.
     */
    private fun orbFor(
        agentId: String?,
        avatarStyleByAgentId: Map<String, Int>,
    ): LettaSearchLeading = agentId
        ?.let { LettaSearchLeading.Orb(orbIndex = avatarStyleByAgentId[it] ?: 0, agentId = it) }
        ?: LettaSearchLeading.None

    private const val DEFAULT_LIMIT = 20
}
