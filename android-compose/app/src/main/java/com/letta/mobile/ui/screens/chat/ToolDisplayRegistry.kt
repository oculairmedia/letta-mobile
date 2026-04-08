package com.letta.mobile.ui.screens.chat

data class ToolDisplayInfo(
    val emoji: String,
    val label: String,
    val detailLine: String? = null,
)

object ToolDisplayRegistry {
    private val registry = mapOf(
        "web_search" to ToolDisplayInfo("🔍", "Searching the web"),
        "archival_memory_search" to ToolDisplayInfo("🧠", "Searching memory"),
        "archival_memory_insert" to ToolDisplayInfo("💾", "Saving to memory"),
        "conversation_search" to ToolDisplayInfo("💬", "Searching conversations"),
        "memory" to ToolDisplayInfo("🧠", "Updating memory"),
        "memory_replace" to ToolDisplayInfo("✏️", "Editing memory"),
        "memory_insert" to ToolDisplayInfo("📝", "Adding to memory"),
        "memory_apply_patch" to ToolDisplayInfo("🩹", "Patching memory"),
        "send_message" to ToolDisplayInfo("📤", "Sending message"),
        "find_tools" to ToolDisplayInfo("🔧", "Discovering tools"),
        "Read" to ToolDisplayInfo("📖", "Reading file"),
        "Write" to ToolDisplayInfo("✍️", "Writing file"),
        "Edit" to ToolDisplayInfo("✏️", "Editing file"),
        "Bash" to ToolDisplayInfo("⚡", "Running command"),
        "BashOutput" to ToolDisplayInfo("⚡", "Running command"),
        "Grep" to ToolDisplayInfo("🔍", "Searching files"),
        "Glob" to ToolDisplayInfo("📁", "Finding files"),
    )

    fun resolve(toolName: String, args: String? = null): ToolDisplayInfo {
        val known = registry[toolName]
        if (known != null) {
            val detail = when (toolName) {
                "web_search" -> args?.take(60)
                "Read", "Write", "Edit" -> args?.take(80)
                "Bash", "BashOutput" -> args?.take(60)
                else -> null
            }
            return known.copy(detailLine = detail ?: known.detailLine)
        }
        return ToolDisplayInfo("🔧", toolName)
    }
}
