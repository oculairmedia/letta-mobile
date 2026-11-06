package com.letta.mobile.data.home.shortcut

/** Existing Home shortcut identities, independent of Home controllers and storage. */
enum class HomeShortcut(val label: String, val description: String?, val group: Group) {
    CONVERSATIONS("Conversations", null, Group.PRIMARY),
    AGENTS("Agents", null, Group.PRIMARY),
    TOOLS("Tools", null, Group.PRIMARY),
    BLOCKS("Blocks", null, Group.PRIMARY),
    TEMPLATES("Starter Agents", "Browse starter agents", Group.SECONDARY),
    PROVIDERS("Providers", "Configure LLM providers", Group.SECONDARY),
    SCHEDULES("Schedules", "Scheduled tasks", Group.SECONDARY),
    RUNS("Runs", "Monitor agent runs", Group.SECONDARY),
    MCP_SERVERS("MCP Servers", "External tool servers", Group.SECONDARY),
    BOT_SETTINGS("Bot Settings", "Configure bot", Group.SECONDARY),
    PROJECTS("Projects", "Project workspace", Group.SECONDARY),
    MODELS("Models", "Browse LLM models", Group.SECONDARY),
    USAGE("Usage", "Token analytics", Group.SECONDARY),
    FAVORITE_AGENT("Favorite Agent", "Quick chat", Group.SECONDARY),
    SETTINGS("Settings", "App preferences", Group.UTILITY),
    TELEMETRY("Telemetry", "Dev latency/event log", Group.UTILITY),
    SYSTEM_ACCESS("System Access", "Device access registry", Group.UTILITY),
    ABOUT("About", "App info & version", Group.UTILITY),
    ;

    enum class Group { PRIMARY, SECONDARY, UTILITY }
}
