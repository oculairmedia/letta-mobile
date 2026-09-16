package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame

/** Content-bearing frame metadata used by the transport flow diagnostics. */
internal fun frameFlowContent(frame: ServerFrame): Triple<String, String, String>? = when (frame) {
    is ServerFrame.AssistantMessage -> Triple(frame.otid ?: frame.id, "assistant_message", frame.content)
    is ServerFrame.ReasoningMessage -> Triple(frame.id, "reasoning_message", frame.reasoning)
    else -> null
}
