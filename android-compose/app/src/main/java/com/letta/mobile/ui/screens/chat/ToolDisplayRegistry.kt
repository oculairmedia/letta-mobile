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
            val detail = extractDetail(toolName, args)
            return known.copy(detailLine = detail ?: known.detailLine)
        }
        // Unknown tool — show first 60 chars of args as detail
        val detail = args?.take(60)?.let { if ((args.length) > 60) "$it…" else it }
        return ToolDisplayInfo("🔧", toolName, detailLine = detail)
    }

    private fun extractDetail(toolName: String, args: String?): String? {
        if (args.isNullOrBlank()) return null
        return when (toolName) {
            "web_search" -> extractJsonStringField(args, "query")?.truncate(60)
                ?: args.take(60)
            "archival_memory_search" -> extractJsonStringField(args, "query")?.truncate(80)
            "archival_memory_insert" -> extractJsonStringField(args, "content")?.truncate(80)
            "conversation_search" -> extractJsonStringField(args, "query")?.truncate(80)
            "memory_replace" -> extractJsonStringField(args, "new_value")?.truncate(80)
            "memory_insert" -> extractJsonStringField(args, "value")?.truncate(80)
                ?: extractJsonStringField(args, "content")?.truncate(80)
            "memory_apply_patch" -> extractJsonStringField(args, "patch")?.truncate(80)
            "find_tools" -> extractJsonStringField(args, "query")?.truncate(60)
                ?: extractJsonStringField(args, "search")?.truncate(60)
            "Read", "Write", "Edit" -> extractJsonStringField(args, "file_path")?.truncate(80)
                ?: args.take(80)
            "Bash", "BashOutput" -> extractJsonStringField(args, "command")?.truncate(60)
                ?: args.take(60)
            "Grep" -> extractJsonStringField(args, "pattern")?.truncate(60)
            "Glob" -> extractJsonStringField(args, "pattern")?.truncate(60)
            else -> null
        }
    }

    private fun extractJsonStringField(json: String, field: String): String? {
        // Lightweight extraction without a JSON parser
        val key = "\"$field\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx < 0) return null
        val quoteStart = json.indexOf('"', colonIdx + 1)
        if (quoteStart < 0) return null
        val sb = StringBuilder()
        var i = quoteStart + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                val next = json[i + 1]
                when (next) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append(' ')
                    't' -> sb.append(' ')
                    else -> { sb.append('\\'); sb.append(next) }
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString().ifBlank { null }
    }

    private fun String.truncate(max: Int): String =
        if (length > max) take(max) + "…" else this
}
