package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.UiToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@JvmInline
internal value class ToolNameToken(val value: String)

@JvmInline
internal value class ToolStatusToken(val value: String) {
    fun isSuccess(): Boolean =
        value.equals("success", ignoreCase = true) ||
            value.equals("completed", ignoreCase = true) ||
            value.equals("ok", ignoreCase = true)

    fun isError(): Boolean =
        value.equals("error", ignoreCase = true) ||
            value.equals("failed", ignoreCase = true)

    fun isDoneStatus(): Boolean =
        value.equals("completed", ignoreCase = true) ||
            value.equals("success", ignoreCase = true) ||
            value.equals("ok", ignoreCase = true)
}

@JvmInline
internal value class ToolStepVerb(val value: String)

@JvmInline
internal value class ToolArgumentPayload(val value: String)

@JvmInline
internal value class MessageRoleToken(val value: String) {
    fun isUser(): Boolean = value.equals("user", ignoreCase = true)

    companion object {
        val User = MessageRoleToken("user")
    }
}

/** Lifecycle state of a tool step, driving the leading status circle. */
internal enum class StepState { Done, Running, Error }

internal fun UiToolCall.stepState(): StepState {
    val status = status?.let(::ToolStatusToken) ?: return StepState.Done
    return when {
        status.isDoneStatus() -> StepState.Done
        status.isError() -> StepState.Error
        else -> StepState.Done
    }
}

/** "Ran ./gradlew …" / "Read Foo.kt" — a friendly verb + short target. */
internal fun UiToolCall.stepLabel(): String {
    val verb = toolStepVerb(ToolNameToken(name))
    val target = truncateToolTarget(primaryToolArgument(ToolArgumentPayload(arguments)))
    return when {
        verb != null && target.isNotBlank() -> "${verb.value}  $target"
        verb != null -> verb.value
        else -> name
    }
}

/** Right-aligned step summary (result first line / duration). */
internal fun UiToolCall.stepSummary(): String {
    val resultLine = result?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
    return when {
        !resultLine.isNullOrBlank() && resultLine.length <= 28 -> resultLine
        executionTimeMs != null -> "${executionTimeMs} ms"
        else -> ""
    }
}

internal fun UiToolCall.isErrorStatus(): Boolean =
    status?.let(::ToolStatusToken)?.isError() == true

internal fun UiToolCall.copyPayload(): String =
    listOfNotNull(
        arguments.takeIf { it.isNotBlank() },
        result?.takeIf { it.isNotBlank() },
    ).joinToString("\n\n").ifBlank { name }

/**
 * Pull the human-meaningful payload out of a tool-call arguments JSON object
 * (e.g. the shell command / code / query) so the card shows that instead of a
 * raw `{"command":"…"}` dump. Falls back to pretty-printed JSON, then the raw
 * string.
 */
internal fun primaryToolArgument(payload: ToolArgumentPayload): String {
    val obj = parseToolArgumentsObject(payload) ?: return payload.value
    return preferredToolArgument(obj) ?: prettyToolArguments(obj, fallback = payload.value)
}

private fun toolStepVerb(toolName: ToolNameToken): ToolStepVerb? {
    val n = toolName.value.lowercase()
    return when {
        TOOL_VERB_RAN.any { n.contains(it) } -> ToolStepVerb("Ran")
        TOOL_VERB_READ.any { n.contains(it) } -> ToolStepVerb("Read")
        TOOL_VERB_WROTE.any { n.contains(it) } -> ToolStepVerb("Wrote")
        TOOL_VERB_EDITED.any { n.contains(it) } -> ToolStepVerb("Edited")
        TOOL_VERB_SEARCHED.any { n.contains(it) } -> ToolStepVerb("Searched")
        TOOL_VERB_FETCHED.any { n.contains(it) } -> ToolStepVerb("Fetched")
        else -> null
    }
}

private fun truncateToolTarget(raw: String, maxLen: Int = 52): String {
    val target = raw.lineSequence().firstOrNull()?.trim().orEmpty()
    return if (target.length > maxLen) target.take(maxLen) + "…" else target
}

private fun parseToolArgumentsObject(payload: ToolArgumentPayload): JsonObject? =
    runCatching { desktopChatJson.parseToJsonElement(payload.value) as? JsonObject }.getOrNull()

private fun preferredToolArgument(obj: JsonObject): String? {
    for (key in PREFERRED_TOOL_ARGUMENT_KEYS) {
        val value = obj[key] as? JsonPrimitive ?: continue
        if (value.isString && value.content.isNotBlank()) return value.content
    }
    return null
}

private fun prettyToolArguments(obj: JsonObject, fallback: String): String =
    runCatching { prettyDesktopJson.encodeToString(JsonObject.serializer(), obj) }.getOrDefault(fallback)

private val TOOL_VERB_RAN = listOf("bash", "shell", "command", "exec", "run", "terminal")
private val TOOL_VERB_READ = listOf("read", "cat", "view", "open")
private val TOOL_VERB_WROTE = listOf("write", "create")
private val TOOL_VERB_EDITED = listOf("edit", "replace", "apply", "patch")
private val TOOL_VERB_SEARCHED = listOf("search", "grep", "glob", "find", "list")
private val TOOL_VERB_FETCHED = listOf("fetch", "http", "web", "curl", "request")

private val PREFERRED_TOOL_ARGUMENT_KEYS = listOf(
    "command", "code", "query", "input", "text", "content", "cmd", "script", "expression",
)

private val prettyDesktopJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

@JvmInline
internal value class OutputLine(val value: String)

internal fun isUnifiedDiffAddedLine(line: OutputLine): Boolean {
    val trimmed = line.value.trimStart()
    return trimmed.startsWith("+") && !trimmed.startsWith("+++")
}

internal fun isUnifiedDiffRemovedLine(line: OutputLine): Boolean {
    val trimmed = line.value.trimStart()
    return trimmed.startsWith("-") && !trimmed.startsWith("---")
}

internal fun isSuccessOutputLine(line: OutputLine): Boolean {
    val lower = line.value.lowercase()
    return lower.contains("build successful") || lower.contains("success") || lower.contains("passed")
}

internal fun isFailureOutputLine(line: OutputLine): Boolean {
    val lower = line.value.lowercase()
    return lower.contains("error") ||
        lower.contains("failed") ||
        lower.contains("exception") ||
        lower.contains("fatal")
}
