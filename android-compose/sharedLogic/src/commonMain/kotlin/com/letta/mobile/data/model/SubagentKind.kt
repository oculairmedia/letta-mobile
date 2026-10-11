package com.letta.mobile.data.model

/**
 * letta-mobile-fxoew.4: the ONE classifier for a subagent's wire
 * `subagentType`.
 *
 * The host registry passes the App Server's display labels through verbatim:
 * "Reflection", "Reflection integration", "General-purpose", "Fork", "Recall".
 * The UI used to compare against a lowercase "reflection" literal, so no
 * reflection run was ever hidden. Every surface that decides visibility (the
 * Android chip bar, rings and linger tick, the desktop Background tasks panel)
 * must ask this classifier instead of comparing strings.
 *
 * letta-mobile-iczm7: letta-code's memory upkeep workers (git-conflict repair, invalid-memory
 * repair) run as subagentType `memory` (wire label "Memory"), always silent. Like reflection
 * (dreaming) they are background work, shown only on the Dreaming surface.
 */
enum class SubagentKind {
    Reflection,
    GeneralPurpose,
    Fork,
    Recall,
    Memory,
    Other,
    ;

    /**
     * Background work the user did not ask to see (sleeptime reflection, its
     * integration step, memory upkeep). Hidden from the active-subagent chrome.
     */
    val isBackground: Boolean
        get() = this == Reflection || this == Memory

    companion object {
        /** Trimmed, case-insensitive; any label starting with "reflection" is [Reflection]. */
        fun fromWire(raw: String?): SubagentKind {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return when {
                normalized.startsWith(REFLECTION_PREFIX) -> Reflection
                normalized in GENERAL_PURPOSE_LABELS -> GeneralPurpose
                normalized == "fork" -> Fork
                normalized == "recall" -> Recall
                normalized == MEMORY -> Memory
                else -> Other
            }
        }

        private const val REFLECTION_PREFIX = "reflection"
        private const val MEMORY = "memory"
        private val GENERAL_PURPOSE_LABELS = setOf("general-purpose", "general purpose", "general_purpose")
    }
}

/** True when this entry is background work hidden from the subagent chrome. */
fun SubagentEntry.isBackgroundSubagent(): Boolean = SubagentKind.fromWire(subagentType).isBackground
