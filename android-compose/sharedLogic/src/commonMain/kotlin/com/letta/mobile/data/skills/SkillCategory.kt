package com.letta.mobile.data.skills

/**
 * Display category for a skill. The backend's `/skills` registry doesn't send a
 * category, so we derive one client-side (like [com.letta.mobile.data.memory]'s
 * MemoryCategories) so the desktop/mobile Skills surfaces can group the catalog
 * into sections the way the design mockups do.
 */
enum class SkillCategory(val label: String) {
    Developer("Developer"),
    Productivity("Productivity"),
    Data("Data & Monitoring"),
    Design("Design"),
    Communication("Communication"),
    Automation("Automation"),
    Other("Other"),
}

object SkillCategories {
    /** Section order for grouped rendering. */
    val order: List<SkillCategory> = listOf(
        SkillCategory.Developer,
        SkillCategory.Productivity,
        SkillCategory.Data,
        SkillCategory.Design,
        SkillCategory.Communication,
        SkillCategory.Automation,
        SkillCategory.Other,
    )

    /** Categorize a skill from its name + tags via keyword heuristics. */
    fun categorize(name: String, tags: List<String> = emptyList()): SkillCategory {
        val hay = (name + " " + tags.joinToString(" ")).lowercase()
        return when {
            hay.containsAny(DEVELOPER) -> SkillCategory.Developer
            hay.containsAny(DESIGN) -> SkillCategory.Design
            hay.containsAny(DATA) -> SkillCategory.Data
            hay.containsAny(COMMUNICATION) -> SkillCategory.Communication
            hay.containsAny(AUTOMATION) -> SkillCategory.Automation
            hay.containsAny(PRODUCTIVITY) -> SkillCategory.Productivity
            else -> SkillCategory.Other
        }
    }

    /**
     * Group [items] by derived category in [order], dropping empty sections.
     * [nameOf]/[tagsOf] adapt an arbitrary skill type to the categorizer so
     * this stays decoupled from the wire model.
     */
    fun <T> grouped(
        items: List<T>,
        nameOf: (T) -> String,
        tagsOf: (T) -> List<String> = { emptyList() },
    ): List<Pair<SkillCategory, List<T>>> {
        val byCategory = items.groupBy { categorize(nameOf(it), tagsOf(it)) }
        return order.mapNotNull { category ->
            val inSection = byCategory[category].orEmpty()
            if (inSection.isEmpty()) null else category to inSection
        }
    }

    private fun String.containsAny(keywords: List<String>): Boolean =
        keywords.any { this.contains(it) }

    private val DEVELOPER = listOf(
        "github", "gitlab", "git", "code", "cli", "api", "sdk", "sentry", "datadog",
        "jupyter", "notebook", "linear", "jira", "deploy", "docker", "kubernetes",
        "letta", "terminal", "shell", "bash", "build", "compiler", "lint", "pr ", "ci",
    )
    private val DESIGN = listOf("figma", "design", "sketch", "photoshop", "canva", "ui", "ux")
    private val DATA = listOf(
        "database", "sql", "query", "analytics", "metric", "monitor", "router",
        "network", "stats", "grafana", "prometheus", "dashboard", "log", "observability",
    )
    private val COMMUNICATION = listOf(
        "slack", "telegram", "discord", "email", "gmail", "mail", "message", "sms",
        "twilio", "chat", "channel",
    )
    private val AUTOMATION = listOf(
        "schedule", "cron", "webhook", "zapier", "automat", "trigger", "heartbeat", "watch", "jules",
    )
    private val PRODUCTIVITY = listOf(
        "notion", "obsidian", "spreadsheet", "sheet", "doc", "calendar", "gog",
        "workspace", "drive", "todo", "task", "note", "1password", "password", "secret",
        "office", "word", "excel",
    )
}
