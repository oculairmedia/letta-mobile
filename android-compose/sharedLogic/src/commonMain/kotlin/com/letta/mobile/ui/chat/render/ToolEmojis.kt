package com.letta.mobile.ui.chat.render

/**
 * The emoji each tool reads as in the chat (letta-mobile-bglj6.1.23). One table, platform-neutral,
 * shared by the legacy Android cards (through [ToolDisplayRegistry]) and the shared KMP rows.
 */
object ToolEmojis {
    /** An unrecognised tool. */
    const val DEFAULT: String = "🔧"

    private const val SEARCH = "🔍"
    private const val MEMORY = "🧠"
    private const val EDIT = "✏️"
    private const val SHELL = "⚡"

    private val byTool: Map<String, String> = mapOf(
        "web_search" to SEARCH,
        "archival_memory_search" to MEMORY,
        "archival_memory_insert" to "💾",
        "conversation_search" to "💬",
        "memory" to MEMORY,
        "memory_replace" to EDIT,
        "memory_insert" to "📝",
        "memory_apply_patch" to "🩹",
        "send_message" to "📤",
        "find_tools" to DEFAULT,
        "Read" to "📖",
        "Write" to "✍️",
        "Edit" to EDIT,
        "Bash" to SHELL,
        "BashOutput" to SHELL,
        "exec_command" to SHELL,
        "functions.exec_command" to SHELL,
        "Grep" to SEARCH,
        "Glob" to "📁",
    )

    /** The tool's emoji, or [DEFAULT]. */
    fun forTool(toolName: String): String = byTool[toolName] ?: DEFAULT
}
