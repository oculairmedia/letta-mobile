package com.letta.mobile.data.search

/** What a command-palette result jumps to. */
enum class PaletteItemKind { Conversation, Agent, Destination }

/**
 * One command-palette result — a conversation, an agent, or a navigation
 * destination. The platform resolves [id]/[kind] to an action; this model just
 * carries display data + the search keys.
 */
data class PaletteItem(
    val id: String,
    val label: String,
    val sublabel: String?,
    val kind: PaletteItemKind,
    /** Optional avatar gradient index for agent/conversation rows. */
    val orbIndex: Int? = null,
    /**
     * Owning agent for a [PaletteItemKind.Conversation] row. Agent rows may omit this:
     * [mascotAgentId] falls back to [id]. Null on a conversation means the orb stays the
     * gradient fallback (no mascot identity to look up).
     */
    val agentId: String? = null,
)

/**
 * Agent whose mascot the row should draw. Destinations have none. Conversation rows without
 * [PaletteItem.agentId] return null so the platform can keep the gradient orb.
 */
fun PaletteItem.mascotAgentId(): String? = when (kind) {
    PaletteItemKind.Agent -> agentId ?: id
    PaletteItemKind.Conversation -> agentId
    PaletteItemKind.Destination -> null
}

/**
 * Shared filtering + grouping for the Cmd+K command palette (Penpot "Search
 * (command palette)"). One implementation so desktop and mobile search the same
 * way over the same sections.
 */
object CommandPalette {
    val sectionOrder: List<PaletteItemKind> = listOf(
        PaletteItemKind.Conversation,
        PaletteItemKind.Agent,
        PaletteItemKind.Destination,
    )

    fun sectionTitle(kind: PaletteItemKind): String = when (kind) {
        PaletteItemKind.Conversation -> "Conversations"
        PaletteItemKind.Agent -> "Agents"
        PaletteItemKind.Destination -> "Go to"
    }

    /** Filter [items] by [query] and group into [sectionOrder], dropping empty sections. */
    fun grouped(items: List<PaletteItem>, query: String): List<Pair<PaletteItemKind, List<PaletteItem>>> {
        val matched = items.filter { TextMatch.matches(query, it.label, it.sublabel) }
        return sectionOrder.mapNotNull { kind ->
            val inSection = matched.filter { it.kind == kind }
            if (inSection.isEmpty()) null else kind to inSection
        }
    }
}
