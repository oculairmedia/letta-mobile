package com.letta.mobile.data.chat.runtime

/** The derived "is the agent working" presence flags a chat surface renders. */
data class ChatStreamingPresence(
    /** Drives the streaming/thinking glow + token-active state. */
    val isStreaming: Boolean,
    /** Drives the typing indicator (the bouncing dots). */
    val isAgentTyping: Boolean,
)

/**
 * Platform-neutral derivation of the streaming/typing presence flags from the
 * projection-derived facts plus the platform's stream signals.
 *
 * This is the if-else chain that previously lived inline in Android's
 * `ChatTimelineObserver`; sharing it lets Android and desktop present "the agent
 * is working" identically, and makes the precedence rules headless-testable.
 *
 * The precedence is deliberate:
 *  - while a client-mode (embedded/local-runtime) turn is in flight the runtime
 *    owns the flags, so the previous values are held;
 *  - otherwise an actively-streaming reply, an open A2UI "thinking" surface, or
 *    a duplicate-initial follow are each enough to show working;
 *  - failing those, a still-sending local message means working — and typing is
 *    suppressed once the tail is a confirmed assistant message (the reply has
 *    started landing, so it's "streaming" rather than "typing").
 */
object ChatStreamingPresencePolicy {
    fun derive(
        previousIsStreaming: Boolean,
        previousIsAgentTyping: Boolean,
        anyServerLocalPending: Boolean,
        tailIsAssistant: Boolean,
        replyStreaming: Boolean,
        clientModeStreamInFlight: Boolean,
        a2uiThinkingActive: Boolean,
        duplicateInitialMessageInFlight: Boolean,
    ): ChatStreamingPresence {
        val isStreaming = when {
            clientModeStreamInFlight -> previousIsStreaming
            replyStreaming -> true
            a2uiThinkingActive -> true
            duplicateInitialMessageInFlight -> true
            else -> anyServerLocalPending
        }
        val isAgentTyping = when {
            clientModeStreamInFlight -> previousIsAgentTyping
            replyStreaming -> true
            a2uiThinkingActive -> true
            duplicateInitialMessageInFlight -> true
            else -> anyServerLocalPending && !tailIsAssistant
        }
        return ChatStreamingPresence(isStreaming = isStreaming, isAgentTyping = isAgentTyping)
    }
}
