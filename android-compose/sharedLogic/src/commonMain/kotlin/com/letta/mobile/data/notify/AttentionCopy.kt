package com.letta.mobile.data.notify

/** The words of an approval notification, shared so both platforms say the same thing. */
object AttentionCopy {
    private const val AGENT_FALLBACK = "Your agent"

    fun approvalTitle(agentName: String?): String =
        "${agentName?.takeIf { it.isNotBlank() } ?: AGENT_FALLBACK} needs your go-ahead"

    fun approvalBody(event: AttentionEvent.ApprovalNeeded): String {
        val tool = event.toolName?.takeIf { it.isNotBlank() }
        return if (tool == null) {
            "A tool call is paused until you allow or deny it."
        } else {
            "$tool is paused until you allow or deny it."
        }
    }

    const val OPEN_ACTION = "Open"
}
