package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_duration_ms
import com.letta.mobile.sharedui.resources.rows_tool_step_label
import com.letta.mobile.sharedui.resources.rows_tool_verb_edited
import com.letta.mobile.sharedui.resources.rows_tool_verb_fetched
import com.letta.mobile.sharedui.resources.rows_tool_verb_ran
import com.letta.mobile.sharedui.resources.rows_tool_verb_read
import com.letta.mobile.sharedui.resources.rows_tool_verb_searched
import com.letta.mobile.sharedui.resources.rows_tool_verb_wrote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// letta-mobile-bglj6.1: lifted from desktop's DesktopChatToolLabels.kt. The verb and the
// duration are resource strings now, so the label is a small model resolved in composition.

internal fun isUserRole(role: String): Boolean = role.equals("user", ignoreCase = true)

internal fun isToolStatusError(status: String?): Boolean =
    status != null && (status.equals("error", ignoreCase = true) || status.equals("failed", ignoreCase = true))

internal fun isToolStatusDone(status: String?): Boolean =
    status != null && DONE_STATUSES.any { status.equals(it, ignoreCase = true) }

private val DONE_STATUSES = listOf("completed", "success", "ok")

/** Lifecycle state of a tool step, driving the leading status circle. */
internal enum class StepState { Done, Running, Error }

internal fun UiToolCall.stepState(): StepState = when {
    isToolStatusError(status) -> StepState.Error
    status == null || settled || isToolStatusDone(status) -> StepState.Done
    else -> StepState.Running
}

internal fun UiToolCall.isErrorStatus(): Boolean = isToolStatusError(status)

/**
 * Completed tools start collapsed; failures, running tools and image tools start expanded
 * (desktop's disclosure policy).
 */
internal fun UiToolCall.shouldInitiallyExpand(): Boolean =
    generatedImageAttachments.isNotEmpty() || !isToolStatusDone(status)

internal fun UiToolCall.disclosureKey(): String =
    toolCallId?.takeIf { it.isNotBlank() } ?: "$name:${arguments.hashCode()}"

internal fun UiToolCall.copyPayload(): String =
    listOfNotNull(
        arguments.takeIf { it.isNotBlank() },
        result?.takeIf { it.isNotBlank() },
    ).joinToString("\n\n").ifBlank { name }

/** A friendly verb + short target ("Ran ./gradlew …"), resolved to text in composition. */
@Immutable
internal data class ToolStepLabel(
    val verb: ToolStepVerb?,
    val target: String,
    val name: String,
)

internal enum class ToolStepVerb(val resource: StringResource, val needles: List<String>) {
    Ran(Res.string.rows_tool_verb_ran, listOf("bash", "shell", "command", "exec", "run", "terminal")),
    Read(Res.string.rows_tool_verb_read, listOf("read", "cat", "view", "open")),
    Wrote(Res.string.rows_tool_verb_wrote, listOf("write", "create")),
    Edited(Res.string.rows_tool_verb_edited, listOf("edit", "replace", "apply", "patch")),
    Searched(Res.string.rows_tool_verb_searched, listOf("search", "grep", "glob", "find", "list")),
    Fetched(Res.string.rows_tool_verb_fetched, listOf("fetch", "http", "web", "curl", "request")),
    ;

    companion object {
        fun forTool(name: String): ToolStepVerb? {
            val lower = name.lowercase()
            return entries.firstOrNull { verb -> verb.needles.any { lower.contains(it) } }
        }
    }
}

internal fun UiToolCall.stepLabel(): ToolStepLabel = ToolStepLabel(
    verb = ToolStepVerb.forTool(name),
    target = truncateToolTarget(primaryToolArgument(arguments)),
    name = name,
)

@Composable
internal fun ToolStepLabel.text(): String {
    val verb = verb ?: return name
    val verbText = stringResource(verb.resource)
    return if (target.isNotBlank()) stringResource(Res.string.rows_tool_step_label, verbText, target) else verbText
}

/** Right-aligned step summary: the result's first short line, else the duration. */
@Composable
internal fun UiToolCall.stepSummary(): String {
    val resultLine = result?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
    return when {
        !resultLine.isNullOrBlank() && resultLine.length <= STEP_SUMMARY_MAX -> resultLine
        executionTimeMs != null -> stringResource(Res.string.rows_duration_ms, executionTimeMs ?: 0L)
        else -> ""
    }
}

private const val STEP_SUMMARY_MAX = 28
private const val TOOL_TARGET_MAX = 52

/**
 * Pulls the human-meaningful payload out of a tool-call arguments JSON object (the shell
 * command, code, query) so the card shows that instead of a raw `{"command":"…"}` dump.
 * Falls back to pretty-printed JSON, then the raw string.
 */
internal fun primaryToolArgument(arguments: String): String {
    val obj = runCatching { RowJson.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
        ?: return arguments
    return preferredToolArgument(obj)
        ?: runCatching { PrettyRowJson.encodeToString(JsonObject.serializer(), obj) }.getOrDefault(arguments)
}

private fun truncateToolTarget(raw: String): String {
    val target = raw.lineSequence().firstOrNull()?.trim().orEmpty()
    return if (target.length > TOOL_TARGET_MAX) target.take(TOOL_TARGET_MAX) + "…" else target
}

private fun preferredToolArgument(obj: JsonObject): String? {
    for (key in PREFERRED_TOOL_ARGUMENT_KEYS) {
        val value = obj[key] as? JsonPrimitive ?: continue
        if (value.isString && value.content.isNotBlank()) return value.content
    }
    return null
}

private val PREFERRED_TOOL_ARGUMENT_KEYS = listOf(
    "command", "code", "query", "input", "text", "content", "cmd", "script", "expression",
)

private val RowJson = Json { ignoreUnknownKeys = true }

private val PrettyRowJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** The newest-first-safe "is this streaming" check for one message. */
internal fun ChatRowContext.isStreaming(message: UiMessage): Boolean =
    streamingMessageId != null && streamingMessageId == message.id

/** Unified-diff and status colouring of plain tool output lines. */
internal enum class OutputLineKind { Added, Removed, Success, Failure, Plain }

internal fun outputLineKind(line: String): OutputLineKind {
    val trimmed = line.trimStart()
    val lower = line.lowercase()
    return when {
        trimmed.startsWith("+") && !trimmed.startsWith("+++") -> OutputLineKind.Added
        trimmed.startsWith("-") && !trimmed.startsWith("---") -> OutputLineKind.Removed
        SUCCESS_NEEDLES.any { lower.contains(it) } -> OutputLineKind.Success
        FAILURE_NEEDLES.any { lower.contains(it) } -> OutputLineKind.Failure
        else -> OutputLineKind.Plain
    }
}

private val SUCCESS_NEEDLES = listOf("build successful", "success", "passed")
private val FAILURE_NEEDLES = listOf("error", "failed", "exception", "fatal")
