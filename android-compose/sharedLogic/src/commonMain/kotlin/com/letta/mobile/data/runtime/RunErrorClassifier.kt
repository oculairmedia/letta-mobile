package com.letta.mobile.data.runtime

/**
 * letta-mobile-bzvro.9 (F09): what kind of run failure this is, so the person knows whether to
 * wait, top up, switch model or compact.
 *
 * Every kind maps 1:1 onto a sanitized failure family of [terminalReasonKind] /
 * [TurnFailureNotices], so classification never exposes the raw provider reason
 * (letta-mobile-o0atv).
 */
enum class RunErrorKind(val family: String, val action: RunErrorAction) {
    RateLimited("rate_limited", RunErrorAction.Retry),
    CreditLimit("credit_limit", RunErrorAction.SwitchModel),
    ModelNotSupported("model_not_supported", RunErrorAction.SwitchModel),
    ContextWindowExceeded("context_window_exceeded", RunErrorAction.Compact),
    Network("network_error", RunErrorAction.Retry),
    Timeout("timeout", RunErrorAction.Retry),
    ConnectionLost(TurnFailureNotices.CONNECTION_LOST_KIND, RunErrorAction.Retry),
    ContentFilter("content_filter", RunErrorAction.SwitchModel),
    ProviderError("provider_error", RunErrorAction.Retry),
    EmptyResponse("empty_response", RunErrorAction.Retry),
    ConversationBusy("conversation_busy", RunErrorAction.None),
    Other("other", RunErrorAction.None),
    ;

    companion object {
        fun forFamily(family: String?): RunErrorKind? = entries.firstOrNull { it.family == family }
    }
}

/** The one contextual action an error card offers. */
enum class RunErrorAction {
    /** Send the last prompt again. */
    Retry,

    /** Open the model picker. */
    SwitchModel,

    /** Compact the conversation (`/compact`). */
    Compact,

    /** Nothing to do but read it. */
    None,
}

object RunErrorClassifier {
    /**
     * Classify a run failure from what the wire said: `loop_error.message`, its `stop_reason`, and
     * the provider's `api_error` code or message when present. Null inputs are ignored; nothing
     * recognisable is [RunErrorKind.Other].
     */
    fun classify(message: String?, stopReason: String? = null, apiError: String? = null): RunErrorKind {
        val evidence = listOfNotNull(apiError, message, stopReason).joinToString(" ")
        return RunErrorKind.forFamily(terminalReasonKind(evidence)) ?: RunErrorKind.Other
    }

    /**
     * Classify an error row as the timeline shows it. A failure notice carries its family's fixed
     * copy, which maps straight back; any other server error text is classified by content. Null
     * when [text] is blank.
     */
    fun classifyDisplayed(text: String?): RunErrorKind? {
        if (text.isNullOrBlank()) return null
        TurnFailureNotices.kindForMessage(text)?.let { family -> return RunErrorKind.forFamily(family) ?: RunErrorKind.Other }
        return classify(text)
    }
}
