package com.letta.mobile.data.runtime

/**
 * letta-mobile-br5g0: user-facing description of a terminal turn failure.
 *
 * [kind] is the sanitized failure family produced by [terminalReasonKind]
 * (letta-mobile-aktss) — never a substring of the raw provider reason, so the
 * letta-mobile-o0atv "raw reason never leaves the redaction boundary" rule
 * still holds for anything derived from it. [message] is fixed per-family copy.
 */
data class TurnFailureNotice(
    val kind: String,
    val message: String,
)

/**
 * letta-mobile-br5g0: decides what (if anything) the chat timeline should show
 * when a run terminates Failed.
 *
 * Two distinct shapes were observed on 2026-07-29 and MUST NOT render alike:
 *
 * 1. **Dead turn** — the main model call failed (e.g. a provider refusal
 *    surfaced as `Model provider error: Provider finish_reason: content_filter`)
 *    and no assistant content was delivered. The user saw nothing at all and
 *    blamed the transport. This gets a visible error row in the timeline.
 * 2. **Delivered-then-failed** — the assistant reply streamed and persisted
 *    (the user got their answer), then a POST-RESPONSE step of the same run
 *    (title/summary generation inheriting the conversation model) hit the same
 *    provider refusal and the run terminal came back Failed. Painting that like
 *    a dead turn is a lie: the turn succeeded from the user's point of view.
 *    v1 renders NOTHING user-facing for this case (telemetry only) — a trailing
 *    aux-step failure is not actionable by the user, and any badge on a correct
 *    answer reads as "your answer is broken". Revisit if aux failures ever
 *    become user-actionable.
 *
 * The delivered/not-delivered distinction is always supplied by the caller from
 * observed turn state (assistant content actually emitted for this turn), never
 * inferred from timing.
 */
object TurnFailureNotices {
    /** Fallback copy for an unclassifiable failure. */
    const val GENERIC_MESSAGE: String = "This turn failed before the assistant could reply."

    /**
     * Returns the notice to render for a Failed terminal, or null when nothing
     * user-facing should be added to the timeline.
     *
     * @param reason raw terminal reason from the run lifecycle. Used ONLY to
     *   derive the sanitized family; it is never returned in [TurnFailureNotice].
     * @param deliveredAssistantContent whether this turn already delivered
     *   assistant content to the timeline (computed from turn state).
     */
    fun forFailedTerminal(
        reason: String?,
        deliveredAssistantContent: Boolean,
    ): TurnFailureNotice? {
        if (deliveredAssistantContent) return null
        val kind = terminalReasonKind(reason) ?: OTHER_KIND
        return TurnFailureNotice(kind = kind, message = messageFor(kind))
    }

    /** Fixed per-family copy. Never interpolates the raw reason. */
    fun messageFor(kind: String?): String = when (kind) {
        "content_filter" ->
            "The model provider refused this request, so no reply was generated. " +
                "Try rephrasing, or switch the conversation to a different model."
        "rate_limited" ->
            "The model provider is rate limited or overloaded right now. " +
                "Wait a moment and send again."
        "timeout" ->
            "The model provider timed out before replying. Send again to retry."
        "provider_error" ->
            "The model provider returned an error, so this turn produced no reply."
        "empty_response" ->
            "The model returned an empty response, so this turn produced no reply."
        "conversation_busy" ->
            "This conversation is still busy with another run. " +
                "Wait for it to finish, then send again."
        "approval_pending" ->
            "The run ended while still waiting for a tool approval, so this turn produced no reply."
        "invalid_tool_call_ids" ->
            "The run ended on an unresolved tool call, so this turn produced no reply."
        "aborted" ->
            "This turn was interrupted before the assistant replied."
        else -> GENERIC_MESSAGE
    }

    private const val OTHER_KIND = "other"
}
