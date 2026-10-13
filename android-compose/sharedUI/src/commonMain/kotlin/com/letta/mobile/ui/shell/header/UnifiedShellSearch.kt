package com.letta.mobile.ui.shell.header

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.home.conversationRecency
import com.letta.mobile.data.home.relativeAgeLabel
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.DisplayNames
import com.letta.mobile.data.search.MessageSearchHit
import com.letta.mobile.data.search.MessageSearchSource
import com.letta.mobile.data.search.TextMatch
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.search.LettaSearchLeading
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.LettaSearchScope
import com.letta.mobile.ui.search.LettaSearchSection

/** A page the palette can go to ("Settings", "Providers"); [id] is the host's own name for it. */
data class UnifiedSearchDestination(val id: String, val label: String)

/**
 * What the unified search looks through. Hosts feed what their graph has; empty lists are fine.
 * Message text is the one thing not matched here: [messageSearch] (the backend's search, or a
 * host-side [com.letta.mobile.data.search.ConversationMessageIndex]) finds it per query, see
 * [rememberUnifiedSearchSections]; null leaves the Messages section out.
 */
data class UnifiedSearchSources(
    val conversations: List<ChatConversationSummary> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val canvases: List<CanvasDocument> = emptyList(),
    val avatarStyleByAgentId: Map<String, Int> = emptyMap(),
    /** The focused agent: "Filter to this agent" narrows to it. */
    val agentId: String? = null,
    val destinations: List<UnifiedSearchDestination> = emptyList(),
    val messageSearch: MessageSearchSource? = null,
)

/** The kind of thing a unified search row opens. */
enum class UnifiedSearchKind(val id: String, val label: String) {
    Conversations("conversations", "Conversations"),
    Messages("messages", "Messages"),
    Agents("agents", "Agents"),
    Canvases("canvases", "Canvases"),
    Destinations("destinations", "Go to"),
}

/**
 * Where the unified search is drawn, and so which kinds it covers (letta-mobile-ckmub.3.9): the
 * header covers canvases, the command palette (a picker mode of the same search) covers the pages
 * to go to. Both find conversations, the text of their messages, and agents.
 */
enum class UnifiedSearchSurface(val kinds: List<UnifiedSearchKind>) {
    Header(listOf(UnifiedSearchKind.Conversations, UnifiedSearchKind.Messages, UnifiedSearchKind.Agents, UnifiedSearchKind.Canvases)),
    Palette(listOf(UnifiedSearchKind.Conversations, UnifiedSearchKind.Messages, UnifiedSearchKind.Agents, UnifiedSearchKind.Destinations)),
}

/**
 * What the user asked the unified search: the typed [text], the "Filter to this agent" checkbox
 * (on by default only inside a chat, see [ShellSearchFilters]), the scope tab
 * ([UnifiedShellSearch.ALL] or a [UnifiedSearchKind.id]) and the [surface] asking.
 */
data class UnifiedSearchQuery(
    val text: String,
    val filterToAgent: Boolean = false,
    val scopeId: String = UnifiedShellSearch.ALL,
    val surface: UnifiedSearchSurface = UnifiedSearchSurface.Header,
) {
    fun includes(kind: UnifiedSearchKind): Boolean =
        kind in surface.kinds && (scopeId == UnifiedShellSearch.ALL || scopeId == kind.id)
}

/** A picked row: what kind it is and the id of the thing to open (for a message, its conversation). */
data class UnifiedSearchHit(val kind: UnifiedSearchKind, val id: String)

/**
 * The one search model behind the shell header's search and the command palette (was desktop's
 * DesktopUnifiedSearch plus sharedLogic's CommandPalette): one query across conversations, message
 * text, agents, canvases and destinations, each surface taking its [UnifiedSearchSurface.kinds].
 * Matching is [TextMatch]'s, the same token-based primitive the mention catalog and the model picker
 * use; message text is found by the host's message search and only ranked and drawn here. The UI
 * halves are `LettaSearch` (header) and `LettaPicker` (palette).
 */
object UnifiedShellSearch {
    /** The default scope; the others narrow to one [UnifiedSearchKind]. */
    const val ALL = "all"

    /** The header's scope tabs. */
    val scopes: List<LettaSearchScope> = scopesFor(UnifiedSearchSurface.Header)

    fun scopesFor(surface: UnifiedSearchSurface): List<LettaSearchScope> =
        listOf(LettaSearchScope(ALL, "All")) + surface.kinds.map { LettaSearchScope(it.id, it.label) }

    /** Row ids carry their kind so a pick routes without a parallel lookup; [parseRowId] reads them. */
    fun rowId(kind: UnifiedSearchKind, id: String): String = "${kind.id}:$id"

    /** The hit behind "conversations:conv-1"; null if malformed or of an unknown kind. */
    fun parseRowId(rowId: String): UnifiedSearchHit? {
        val separator = rowId.indexOf(':')
        if (separator <= 0 || separator == rowId.lastIndex) return null
        val kind = UnifiedSearchKind.entries.firstOrNull { it.id == rowId.substring(0, separator) } ?: return null
        val id = rowId.substring(separator + 1)
        // A message row opens its conversation; its own id only keeps rows apart.
        return UnifiedSearchHit(kind, if (kind == UnifiedSearchKind.Messages) id.substringBefore(MESSAGE_ID_SEPARATOR) else id)
    }

    /**
     * The sections for [query] over [sources] and the message-text [messages] the host found for it,
     * in the surface's order. [UnifiedSearchQuery.filterToAgent]
     * restricts conversations, messages and canvases to the focused agent; agents are never
     * restricted, since narrowing the agent list to the agent already selected would leave one row.
     */
    fun sections(
        query: UnifiedSearchQuery,
        sources: UnifiedSearchSources,
        messages: List<MessageSearchHit> = emptyList(),
        limitPerSection: Int = DEFAULT_LIMIT,
    ): List<LettaSearchSection> {
        val scopedAgentId = sources.agentId.takeIf { query.filterToAgent }
        val search = SectionSearch(query.text, SearchedSources(sources, messages), scopedAgentId, limitPerSection)
        return query.surface.kinds
            .filter(query::includes)
            .map { kind -> LettaSearchSection(kind.label, search.rows(kind)) }
            .filter { it.rows.isNotEmpty() }
    }

    /** The sources a search runs over, with the message hits found for its query. */
    private class SearchedSources(val sources: UnifiedSearchSources, val messages: List<MessageSearchHit>)

    private class SectionSearch(
        val query: String,
        searched: SearchedSources,
        val scopedAgentId: String?,
        val limit: Int,
    ) {
        private val sources = searched.sources
        private val messageHits = searched.messages
        private val agentNames: Map<String, String> by lazy { sources.agents.associate { it.id.value to it.name } }

        fun rows(kind: UnifiedSearchKind): List<LettaSearchRow> = when (kind) {
            UnifiedSearchKind.Conversations -> conversations()
            UnifiedSearchKind.Messages -> messages()
            UnifiedSearchKind.Agents -> agents()
            UnifiedSearchKind.Canvases -> canvases()
            UnifiedSearchKind.Destinations -> destinations()
        }

        private fun inScope(agentId: String?): Boolean = scopedAgentId == null || agentId == scopedAgentId

        private fun conversations(): List<LettaSearchRow> = sources.conversations
            .filter { inScope(it.agentId) }
            .filter { TextMatch.matches(query, it.displayTitle(), agentNameOf(it)) }
            .sortedByDescending { conversationRecency(it.updatedAtLabel) }
            .take(limit)
            .map { conversation ->
                LettaSearchRow(
                    id = rowId(UnifiedSearchKind.Conversations, conversation.id),
                    label = conversation.displayTitle(),
                    // Scoped to one agent, every row would repeat the agent name you already gave.
                    sublabel = agentNameOf(conversation).takeIf { scopedAgentId == null },
                    meta = relativeAgeLabel(conversation.updatedAtLabel),
                    leading = orbFor(conversation.agentId, agentNameOf(conversation)),
                )
            }

        /** Message text the host found; only a typed query searches it. */
        private fun messages(): List<LettaSearchRow> {
            if (query.isBlank()) return emptyList()
            val titles = sources.conversations.associateBy { it.id }
            return messageHits
                .filter { inScope(it.agentId) }
                .distinctBy { it.conversationId to it.messageId }
                .take(limit)
                .mapIndexed { index, hit ->
                    val conversation = titles[hit.conversationId]
                    LettaSearchRow(
                        id = rowId(UnifiedSearchKind.Messages, "${hit.conversationId}$MESSAGE_ID_SEPARATOR${hit.messageId ?: index}"),
                        label = snippet(hit.text, query),
                        sublabel = conversation?.displayTitle(),
                        meta = hit.date?.let(::relativeAgeLabel),
                        leading = orbFor(hit.agentId ?: conversation?.agentId),
                    )
                }
        }

        private fun agents(): List<LettaSearchRow> = sources.agents
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

        private fun canvases(): List<LettaSearchRow> = sources.canvases
            .filter { it.agentId == null || inScope(it.agentId) }
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

        private fun destinations(): List<LettaSearchRow> = sources.destinations
            .filter { TextMatch.matches(query, it.label) }
            .take(limit)
            .map { LettaSearchRow(id = rowId(UnifiedSearchKind.Destinations, it.id), label = it.label, leading = LettaSearchLeading.Icon(LettaIcons.ArrowForward)) }

        /** The roster's name for the conversation's agent: the summary's own field can hold a raw id, never shown. */
        private fun agentNameOf(conversation: ChatConversationSummary): String {
            val agentId = conversation.agentId ?: return conversation.agentName
            return agentNames[agentId] ?: DisplayNames.agent(conversation.agentName, agentId)
        }

        /** The owning agent's avatar (mascot or initial tile); a row with no owning agent gets none. */
        private fun orbFor(agentId: String?, agentName: String? = null): LettaSearchLeading = agentId
            ?.let { id ->
                val name = agentName?.takeIf { it.isNotBlank() } ?: agentNames[id]
                LettaSearchLeading.Orb(orbIndex = sources.avatarStyleByAgentId[id] ?: 0, agentId = id, name = name)
            }
            ?: LettaSearchLeading.None
    }

    /** One line of [text] around the first word of [query], so the row shows why it matched. */
    internal fun snippet(text: String, query: String): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        if (flat.length <= SNIPPET_LENGTH) return flat
        val firstWord = query.trim().split(WHITESPACE).firstOrNull().orEmpty()
        val at = if (firstWord.isEmpty()) -1 else flat.indexOf(firstWord, ignoreCase = true)
        val start = (at - SNIPPET_LEAD).coerceIn(0, flat.length - SNIPPET_LENGTH)
        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (start + SNIPPET_LENGTH < flat.length) ELLIPSIS else ""
        return prefix + flat.substring(start, start + SNIPPET_LENGTH).trim() + suffix
    }

    private const val DEFAULT_LIMIT = 20
    private const val MESSAGE_ID_SEPARATOR = "#"
    private const val SNIPPET_LENGTH = 80
    private const val SNIPPET_LEAD = 24
    private const val ELLIPSIS = "…"
    private val WHITESPACE = Regex("\\s+")
}
