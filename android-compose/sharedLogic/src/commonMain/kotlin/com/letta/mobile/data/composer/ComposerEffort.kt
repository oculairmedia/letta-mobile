package com.letta.mobile.data.composer

import kotlinx.serialization.Serializable

/**
 * Reasoning-effort levels offered in the composer effort popover (Penpot
 * "Effort popover (real composer)"): None … Max. Shared in commonMain so the
 * desktop popover and the mobile effort sheet present the same ordered set.
 *
 * letta-mobile-bzvro.18 (F18): the full upstream `reasoning_effort` set (`none`, `minimal`,
 * `low`, `medium`, `high`, `xhigh`, `max`); [wire] is the literal `update_model` sends.
 */
@Serializable
enum class ComposerEffort(val label: String, val wire: String) {
    None("None", "none"),
    Minimal("Minimal", "minimal"),
    Low("Low", "low"),
    Medium("Medium", "medium"),
    High("High", "high"),
    XHigh("Extra high", "xhigh"),
    Max("Max", "max");

    companion object {
        /** The level an upstream `reasoning_effort` literal names, ignoring case; null when unknown. */
        fun fromWire(effort: String?): ComposerEffort? =
            entries.firstOrNull { it.wire.equals(effort?.trim(), ignoreCase = true) }

        /** "Extra high" for `xhigh`; a literal outside the ladder reads as sent. */
        fun labelOf(effort: String): String = fromWire(effort)?.label ?: effort

        /** [efforts] in this ladder's order (None … Max); literals outside it keep their order, last. */
        fun sorted(efforts: List<String>): List<String> {
            val (known, unknown) = efforts.distinct().partition { fromWire(it) != null }
            return known.sortedBy { fromWire(it)?.ordinal } + unknown
        }
    }

    fun increase(): ComposerEffort {
        val allEntries = ComposerEffort.entries
        val index = allEntries.indexOf(this)
        return if (index < allEntries.size - 1) allEntries[index + 1] else this
    }

    fun decrease(): ComposerEffort {
        val allEntries = ComposerEffort.entries
        val index = allEntries.indexOf(this)
        return if (index > 0) allEntries[index - 1] else this
    }
}

/**
 * State holding both the thinking toggle and the effort level.
 */
@Serializable
data class ComposerEffortState(
    val thinking: Boolean = true,
    val effort: ComposerEffort = ComposerEffort.Medium
) {
    fun toggleThinking(): ComposerEffortState = copy(thinking = !thinking)
    fun increaseEffort(): ComposerEffortState = copy(effort = effort.increase())
    fun decreaseEffort(): ComposerEffortState = copy(effort = effort.decrease())
}
