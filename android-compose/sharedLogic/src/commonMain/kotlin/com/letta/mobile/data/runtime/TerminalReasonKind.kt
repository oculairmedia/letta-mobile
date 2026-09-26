package com.letta.mobile.data.runtime

/**
 * letta-mobile-aktss: sanitized classification of a terminal failure reason.
 * Returns a fixed category token, never any substring of the reason itself,
 * so the o0atv no-secrets guarantee is preserved. Categories mirror the
 * failure families letta-code actually produces (run error details and
 * provider passthroughs) — extend [TERMINAL_REASON_FAMILIES] as new families
 * are identified.
 */
internal fun terminalReasonKind(reason: String?): String? {
    if (reason.isNullOrBlank()) return null
    val r = reason.lowercase()
    return TERMINAL_REASON_FAMILIES.firstOrNull { r.isIn(it) }?.kind ?: "other"
}

/** A failure family: matched when the reason contains any of [anyOf], or all of [allOf]. */
private class ReasonFamily(
    val kind: String,
    val anyOf: List<String> = emptyList(),
    val allOf: List<String> = emptyList(),
)

private fun String.isIn(family: ReasonFamily): Boolean =
    family.anyOf.any { it in this } || (family.allOf.isNotEmpty() && family.allOf.all { it in this })

/** Order matters: specific families are matched before generic ones. */
private val TERMINAL_REASON_FAMILIES = listOf(
    // Provider refusal surfaced as an OpenAI-compat finish_reason
    // (e.g. "Model provider error: Provider finish_reason: content_filter").
    ReasonFamily("content_filter", anyOf = listOf("content_filter", "refusal")),
    // letta-mobile-qygvv.16: SESSION_LOST_TERMINAL_REASON, before the generic families it
    // would otherwise fall into.
    ReasonFamily("connection_lost", anyOf = listOf("connection lost")),
    ReasonFamily("approval_pending", anyOf = listOf("waiting for approval")),
    ReasonFamily("invalid_tool_call_ids", anyOf = listOf("invalid tool call ids")),
    ReasonFamily("conversation_busy", allOf = listOf("conversation", "busy")),
    ReasonFamily("empty_response", anyOf = listOf("empty content in", "empty response")),
    ReasonFamily("rate_limited", anyOf = listOf("rate limit", "429", "overloaded", "529")),
    ReasonFamily("timeout", anyOf = listOf("timed out", "timeout")),
    ReasonFamily("provider_error", anyOf = listOf("model provider error", "provider")),
    ReasonFamily("aborted", anyOf = listOf("abort", "cancel", "interrupt")),
)
