package com.letta.mobile.data.runtime

/**
 * letta-mobile-bzvro.7 (F07): the agent loop's phase, from `update_loop_status.status`.
 *
 * The upstream `LoopStatus` union (letta-code 0.29 through 0.33) is open; a token this client does
 * not know yet reads as [Unknown] rather than failing, and keeps the line showing "Working".
 */
enum class LoopPhase(val wire: String?) {
    SendingRequest("SENDING_API_REQUEST"),
    WaitingForResponse("WAITING_FOR_API_RESPONSE"),
    Retrying("RETRYING_API_REQUEST"),
    ProcessingResponse("PROCESSING_API_RESPONSE"),
    ExecutingClientTool("EXECUTING_CLIENT_SIDE_TOOL"),
    ExecutingCommand("EXECUTING_COMMAND"),
    WaitingOnApproval("WAITING_ON_APPROVAL"),
    WaitingOnInput("WAITING_ON_INPUT"),
    Unknown(null),
    ;

    /** The loop is parked waiting for the person's next message: nothing to show. */
    val isIdle: Boolean get() = this == WaitingOnInput

    companion object {
        fun fromWire(status: String?): LoopPhase {
            val token = status?.trim()?.uppercase().orEmpty()
            if (token.isEmpty()) return Unknown
            return entries.firstOrNull { it.wire == token } ?: Unknown
        }
    }
}
