package com.letta.mobile.data.transport.appserver

/** What a Letta `stop_reason` means for the turn it arrives on. */
enum class AppServerTurnBoundary {
    /** The turn ended normally, including by a step, token or tool-rule limit. */
    Completed,
    Cancelled,
    Failed,

    /** Not an end: the run is paused on a `control_request` and continues after the approval. */
    AwaitingApproval,

    /** Not an end: a reason this client does not recognise, e.g. a provider's mid-turn `tool_use`. */
    Continuing,
}

/**
 * The one reading of `stop_reason`, shared by every path that decides a turn boundary.
 *
 * The protocol lifecycle ends a turn on a stop reason other than `requires_approval`. Terminal
 * reasons are the upstream `StopReasonType` (@letta-ai/letta-client `resources/runs/runs.d.ts`,
 * 0.32.10) plus the provider finish reasons the lmstudio / OpenAI-compat path surfaces instead
 * (`stop_sequence`, `max_tokens`, `length`). An unrecognised reason is deliberately NOT terminal:
 * provider reasons such as `tool_use` can arrive mid-turn, and `turn_finished` (0.32+) or the idle
 * timeout still closes a turn that really ended.
 */
object AppServerStopReason {
    const val REQUIRES_APPROVAL: String = "requires_approval"

    private val COMPLETED: Set<String> = setOf(
        "end_turn",
        "max_steps",
        "max_tokens_exceeded",
        "no_tool_call",
        "tool_rule",
        // Provider finish reasons (lmstudio / OpenAI-compat, e.g. MiniMax-M3 emits `length`).
        "stop_sequence",
        "max_tokens",
        "length",
    )

    private val FAILED: Set<String> = setOf(
        "error",
        "llm_api_error",
        "invalid_llm_response",
        "invalid_tool_call",
        "insufficient_credits",
        "context_window_overflow_in_system_prompt",
    )

    /** A stop frame with no reason still ends the turn; it has always been read as completed. */
    fun boundaryOf(stopReason: String?): AppServerTurnBoundary = when (stopReason) {
        null -> AppServerTurnBoundary.Completed
        REQUIRES_APPROVAL -> AppServerTurnBoundary.AwaitingApproval
        "cancelled" -> AppServerTurnBoundary.Cancelled
        in COMPLETED -> AppServerTurnBoundary.Completed
        in FAILED -> AppServerTurnBoundary.Failed
        else -> AppServerTurnBoundary.Continuing
    }

    fun isTerminal(stopReason: String?): Boolean = when (boundaryOf(stopReason)) {
        AppServerTurnBoundary.AwaitingApproval, AppServerTurnBoundary.Continuing -> false
        else -> true
    }
}
