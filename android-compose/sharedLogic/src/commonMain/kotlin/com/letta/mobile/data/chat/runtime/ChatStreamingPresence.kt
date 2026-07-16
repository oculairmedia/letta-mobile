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
        // letta-mobile-c4igq.7: true while a chat turn is in flight on the
        // transport (from turn start until the real terminal), spanning ALL tool
        // rounds. Without it, a multi-step agentic turn drops presence in the
        // inter-round gap (replyStreaming briefly false, tail is the post-tool
        // assistant text), flickering the thinking indicator / send button and
        // making the turn look finished between rounds. Holding presence while
        // the turn is genuinely in flight keeps "the agent is working" steady
        // across rounds. Defaulted so existing call sites/tests are unaffected.
        turnInFlight: Boolean = false,
    ): ChatStreamingPresence {
        val isStreaming = when {
            clientModeStreamInFlight -> previousIsStreaming
            replyStreaming -> true
            a2uiThinkingActive -> true
            duplicateInitialMessageInFlight -> true
            turnInFlight -> true
            else -> anyServerLocalPending
        }
        val isAgentTyping = when {
            clientModeStreamInFlight -> previousIsAgentTyping
            replyStreaming -> true
            a2uiThinkingActive -> true
            duplicateInitialMessageInFlight -> true
            // While a turn is in flight, hold typing/thinking presence EXCEPT
            // when the tail is a settled assistant message (mid-round reply text
            // already landed → show the streaming glow, not the typing dots),
            // matching the anyServerLocalPending branch's tail rule.
            turnInFlight -> !tailIsAssistant
            else -> anyServerLocalPending && !tailIsAssistant
        }
        return ChatStreamingPresence(isStreaming = isStreaming, isAgentTyping = isAgentTyping)
    }
}
