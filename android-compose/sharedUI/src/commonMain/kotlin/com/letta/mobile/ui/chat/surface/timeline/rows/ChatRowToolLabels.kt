package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.tools.LocalToolPresenters

// letta-mobile-bglj6.1: lifted from desktop's DesktopChatToolLabels.kt. How a step reads (its verb and
// target) is the tool presenters' (com.letta.mobile.data.chat.tools); these are the row's own rules.

internal fun isUserRole(role: String): Boolean = role.equals("user", ignoreCase = true)


/**
 * A step starts collapsed, whatever its state; one that generated images starts open, as the
 * images are its output.
 */
internal fun UiToolCall.shouldInitiallyExpand(): Boolean =
    generatedImageAttachments.isNotEmpty()

internal fun UiToolCall.disclosureKey(): String =
    toolCallId?.takeIf { it.isNotBlank() } ?: "$name:${arguments.hashCode()}"

/** The newest-first-safe "is this streaming" check for one message. */
internal fun ChatRowContext.isStreaming(message: UiMessage): Boolean =
    streamingMessageId != null && streamingMessageId == message.id

/** [this] call as a tool presenter reads it. */
internal fun UiToolCall.facts(): ToolCallFacts = ToolCallFacts(name, arguments, result)

/** [call]'s step line from the provided presenters, worked out again only when the call or the presenters change. */
@Composable
internal fun rememberToolStepLine(call: UiToolCall): ToolStepLine {
    val presenters = LocalToolPresenters.current
    return remember(call, presenters) { presenters.line(call.facts()) }
}
