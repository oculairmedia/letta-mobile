package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage

/**
 * A fixture frame as the host delivers it (letta-mobile-nbha6): every row-bearing frame carries the
 * logical id the host minted, and a text frame carries a `text_seq` that grows with its cumulative
 * text. Fixtures written before the host stamped frames name a message by its own id, so that id is
 * the logical id here; a tool call is `tc-<tool_call_id>` and its return `tr-<tool_call_id>`. A frame
 * that already carries a stamp is left alone.
 *
 * The text length stands in for `text_seq` because fixture text only ever grows; tests that exercise
 * the sequence itself set `textSeq` explicitly.
 */
internal fun hostStamped(message: LettaMessage): LettaMessage = when {
    message.logicalMessageId != null -> message
    message is AssistantMessage -> message.copy(logicalMessageId = message.id, textSeq = message.content.length.coerceAtLeast(1))
    message is ReasoningMessage -> message.copy(logicalMessageId = message.id, textSeq = message.reasoning.length.coerceAtLeast(1))
    message is ToolCallMessage -> message.copy(logicalMessageId = message.firstCallId()?.let { "tc-$it" })
    message is ToolReturnMessage -> message.copy(logicalMessageId = message.toolCallId?.let { "tr-$it" })
    else -> message
}

private fun ToolCallMessage.firstCallId(): String? =
    effectiveToolCalls.firstOrNull()?.effectiveId?.takeIf { it.isNotBlank() }
