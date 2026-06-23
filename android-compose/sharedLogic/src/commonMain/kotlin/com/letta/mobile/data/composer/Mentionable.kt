package com.letta.mobile.data.composer

/** What an `@mention` references (drives the section + icon in the picker). */
enum class MentionKind { File, Agent, Memory }

/**
 * A single `@`-mentionable entity — a file, another agent, or a memory block —
 * that gets pulled into the conversation context (Penpot "Composer (@ mentions)").
 */
data class Mentionable(
    val id: String,
    val label: String,
    val sublabel: String?,
    val kind: MentionKind,
    /** Text inserted into the composer when selected (defaults to the label). */
    val insertText: String = label,
)

/**
 * Shared grouping + filtering for the `@mention` picker, so desktop and mobile
 * render the same FILES / AGENTS / MEMORY sections from one source of truth.
 */
object MentionCatalog {
    /** Section display order (matches the design board). */
    val sectionOrder: List<MentionKind> = listOf(MentionKind.File, MentionKind.Agent, MentionKind.Memory)

    fun sectionTitle(kind: MentionKind): String = when (kind) {
        MentionKind.File -> "Files"
        MentionKind.Agent -> "Agents"
        MentionKind.Memory -> "Memory"
    }

    /** Case-insensitive match against label, sublabel, and insert text. */
    fun matches(item: Mentionable, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return item.label.contains(q, ignoreCase = true) ||
            item.insertText.contains(q, ignoreCase = true) ||
            (item.sublabel?.contains(q, ignoreCase = true) == true)
    }

    /** Filter then group [items] by kind, in [sectionOrder], dropping empty sections. */
    fun grouped(items: List<Mentionable>, query: String): List<Pair<MentionKind, List<Mentionable>>> {
        val matched = items.filter { matches(it, query) }
        return sectionOrder.mapNotNull { kind ->
            val inSection = matched.filter { it.kind == kind }
            if (inSection.isEmpty()) null else kind to inSection
        }
    }
}
