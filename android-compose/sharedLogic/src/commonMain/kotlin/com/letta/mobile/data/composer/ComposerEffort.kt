package com.letta.mobile.data.composer

import kotlinx.serialization.Serializable

/**
 * Reasoning-effort levels offered in the composer effort popover (Penpot
 * "Effort popover (real composer)"): Minimal … Max. Shared in commonMain so the
 * desktop popover and the mobile effort sheet present the same ordered set.
 */
@Serializable
enum class ComposerEffort(val label: String) {
    Minimal("Minimal"),
    Low("Low"),
    Medium("Medium"),
    High("High"),
    Max("Max");

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
