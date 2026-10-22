package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Composable
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_duration_ms
import org.jetbrains.compose.resources.stringResource

// letta-mobile-bglj6.1: lifted from desktop's DesktopChatToolLabels.kt. How a step reads (its verb and
// target) is the tool presenters' (com.letta.mobile.data.chat.tools); these are the row's own rules.

internal fun isUserRole(role: String): Boolean = role.equals("user", ignoreCase = true)

internal fun isToolStatusError(status: String?): Boolean =
    status != null && (status.equals("error", ignoreCase = true) || status.equals("failed", ignoreCase = true))

internal fun isToolStatusDone(status: String?): Boolean =
    status != null && DONE_STATUSES.any { status.equals(it, ignoreCase = true) }

private val DONE_STATUSES = listOf("completed", "success", "ok")

internal fun UiToolCall.isErrorStatus(): Boolean = isToolStatusError(status)

/**
 * Completed tools start collapsed; failures, running tools and image tools start expanded
 * (desktop's disclosure policy).
 */
internal fun UiToolCall.shouldInitiallyExpand(): Boolean =
    generatedImageAttachments.isNotEmpty() || !isToolStatusDone(status)

internal fun UiToolCall.disclosureKey(): String =
    toolCallId?.takeIf { it.isNotBlank() } ?: "$name:${arguments.hashCode()}"

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
