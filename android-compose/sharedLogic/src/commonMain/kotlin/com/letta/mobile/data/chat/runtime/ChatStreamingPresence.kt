package com.letta.mobile.data.chat.runtime

/** The derived "is the agent working" presence flags a chat surface renders. */
data class ChatStreamingPresence(
    /** Drives the streaming/thinking glow + token-active state. */
    val isStreaming: Boolean,
    /** Drives the typing indicator (the bouncing dots). */
    val isAgentTyping: Boolean,
)

/**
 * Inputs to the presence derivation. Bundled into a record so the call site can
 * build it once (the projection and the stream signals typically come from the
 * same combined flow) and pass a single value, removing the "many arguments"
 * smell on the derivation function.
 *
 * Defaults are set so each individual use can override just the flags it cares
 * about — but in practice the production sites build a full record.
 *
 * @property previousIsStreaming held while a client-mode stream owns the flags.
 * @property previousIsAgentTyping held while a client-mode stream owns the flags.
 * @property anyServerLocalPending true while a still-sending local message is pending.
 * @property tailIsAssistant true if the timeline tail is a settled assistant message.
 * @property replyStreaming true while a streaming reply is producing tokens.
 * @property clientModeStreamInFlight true while a client-mode (embedded/local) turn is in flight.
 * @property a2uiThinkingActive true while an A2UI thinking surface is open.
 * @property duplicateInitialMessageInFlight true while a duplicate-initial follow is showing.
 * @property turnInFlight letta-mobile-c4igq.7: true while a chat turn is in flight
 *   on the transport (from turn start until the real terminal), spanning ALL tool
 *   rounds. Without it, a multi-step agentic turn drops presence in the
 *   inter-round gap (replyStreaming briefly false, tail is the post-tool
 *   assistant text), flickering the thinking indicator / send button and
 *   making the turn look finished between rounds.
 * @property projectionRunActive letta-mobile-dir4k: true iff the projection still
 *   says a server run is pending for this conversation. The transport-side
 *   `turnInFlight` can stay stale for one observer cycle after the projection
 *   has folded the run — when the projection says "done" we must NOT honor a
 *   stale `turnInFlight=true`. Defaulted to `turnInFlight` so existing call
 *   sites/tests that don't pass it explicitly behave as before (the projection
 *   mask is a no-op until the caller threads the new flag through).
 */
data class ChatStreamInputs(
    val previousIsStreaming: Boolean = false,
    val previousIsAgentTyping: Boolean = false,
    val anyServerLocalPending: Boolean = false,
    val tailIsAssistant: Boolean = false,
    val replyStreaming: Boolean = false,
    val clientModeStreamInFlight: Boolean = false,
    val a2uiThinkingActive: Boolean = false,
    val duplicateInitialMessageInFlight: Boolean = false,
    val turnInFlight: Boolean = false,
    val projectionRunActive: Boolean = turnInFlight,
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
 *  - letta-mobile-dir4k: `turnInFlight` is masked by `projectionRunActive`.
 *    The transport-side `turnInFlight` can stay stale for one observer cycle
 *    after the projection has folded the run (the projection settles before
 *    the transport's `ActiveTurn` entry is removed, e.g. when an emitTurnFrame
 *    for the engine path's terminal hasn't run yet). When the projection
 *    says "done" we must NOT honor a stale `turnInFlight=true` — that is the
 *    exact combination that left the composer stuck on "Thinking…" + red
 *    cancel while the run disclosure correctly showed "Worked for 5.0s".
 */
object ChatStreamingPresencePolicy {
    fun derive(inputs: ChatStreamInputs): ChatStreamingPresence {
        // letta-mobile-dir4k: mask stale `turnInFlight` with the projection.
        // If the projection says the run is settled, the turn cannot be in
        // flight for presence purposes, even if the transport's ActiveTurn
        // entry hasn't been retired yet.
        val effectiveTurnInFlight = inputs.turnInFlight && inputs.projectionRunActive
        val isStreaming = when {
            inputs.clientModeStreamInFlight -> inputs.previousIsStreaming
            inputs.replyStreaming -> true
            inputs.a2uiThinkingActive -> true
            inputs.duplicateInitialMessageInFlight -> true
            effectiveTurnInFlight -> true
            else -> inputs.anyServerLocalPending
        }
        val isAgentTyping = when {
            inputs.clientModeStreamInFlight -> inputs.previousIsAgentTyping
            inputs.replyStreaming -> true
            inputs.a2uiThinkingActive -> true
            inputs.duplicateInitialMessageInFlight -> true
            // While a turn is in flight, hold typing/thinking presence EXCEPT
            // when the tail is a settled assistant message (mid-round reply text
            // already landed → show the streaming glow, not the typing dots),
            // matching the anyServerLocalPending branch's tail rule. The
            // projection mask applies the same way it does for [isStreaming]:
            // a settled projection must NOT keep the typing dots animating.
            effectiveTurnInFlight -> !inputs.tailIsAssistant
            else -> inputs.anyServerLocalPending && !inputs.tailIsAssistant
        }
        return ChatStreamingPresence(isStreaming = isStreaming, isAgentTyping = isAgentTyping)
    }
}
