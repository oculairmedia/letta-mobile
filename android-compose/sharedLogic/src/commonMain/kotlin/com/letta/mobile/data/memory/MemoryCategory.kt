package com.letta.mobile.data.memory

/**
 * Semantic category a memory block belongs to, used to color-code blocks in the
 * Memory surfaces (Phase 6 / spec §1.1b). Platforms map each category to their
 * `category*` theme color; the categorization itself lives here so desktop and
 * mobile agree on which block is which color.
 */
enum class MemoryCategory { Persona, Human, Onboarding, Project, Archival }

object MemoryCategories {
    /**
     * Classify a block by its label. Labels are matched case-insensitively and
     * by substring so common variants ("human", "user_profile", "project_x")
     * land in the right bucket; anything unrecognized is [Archival] (neutral).
     */
    fun categorize(label: String?): MemoryCategory {
        val key = label?.lowercase()?.trim().orEmpty()
        return when {
            key.isEmpty() -> MemoryCategory.Archival
            key.contains("persona") || key.contains("agent") -> MemoryCategory.Persona
            key.contains("human") || key.contains("user") || key.contains("profile") -> MemoryCategory.Human
            key.contains("onboard") || key.contains("welcome") || key.contains("setup") -> MemoryCategory.Onboarding
            key.contains("project") || key.contains("task") || key.contains("goal") -> MemoryCategory.Project
            key.contains("archiv") || key.contains("recall") || key.contains("memory") -> MemoryCategory.Archival
            else -> MemoryCategory.Archival
        }
    }
}
