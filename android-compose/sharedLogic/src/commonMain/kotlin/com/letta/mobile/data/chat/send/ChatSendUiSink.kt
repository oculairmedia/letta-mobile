package com.letta.mobile.data.chat.send

/**
 * Platform-neutral UI seam for [ChatSendCoordinator].
 *
 * The shared coordinator owns the send orchestration (optimistic bubble,
 * otid reconciliation, pending-send queue, turn lifecycle) but must NOT
 * depend on the Compose-coupled `ChatUiState` type, which lives in the
 * `jvmAndAndroid` source set. Every mutation the Android coordinator used to
 * perform inline against `uiState.value` is expressed here as a semantic
 * callback so each platform can apply it to its own UI-state container.
 *
 * Implementations are expected to be cheap and thread-confined by the caller
 * (the coordinator drives them from a single collector + the send launch).
 */
interface ChatSendUiSink {
    /** Current error string, used by end-of-turn resolution (first-wins). */
    fun currentError(): String?

    /** Whether the UI currently believes a turn is streaming. */
    fun isStreaming(): Boolean

    /** Whether the UI currently shows the agent-typing indicator. */
    fun isAgentTyping(): Boolean

    /**
     * A send was accepted by the transport (optimistic dispatch). When
     * [conversationId] is non-null the conversation is now Ready. Streaming
     * and agent-typing turn on; any prior error clears.
     */
    fun onSendDispatched(conversationId: String?)

    /**
     * A send was queued behind an in-flight turn. The conversation is Ready,
     * streaming + agent-typing turn on, error clears.
     */
    fun onSendQueued(conversationId: String)

    /** A send could not be accepted; surface [message] and stop streaming/typing. */
    fun onSendFailed(message: String)

    /** Surface [message] without otherwise changing streaming/typing state. */
    fun onError(message: String?)

    /** A turn started for [conversationId]: Ready, streaming + typing on, error cleared. */
    fun onTurnStarted(conversationId: String)

    /**
     * A non-replay message delta landed for [conversationId]: Ready, streaming
     * + typing on, error cleared.
     */
    fun onMessageDelta(conversationId: String)

    /** Usage statistics for the active turn (first-wins on the shim). */
    fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int)

    /** End of the active turn: streaming + typing off, error set to [error]. */
    fun onTurnFinished(error: String?)

    /** The turn is visually complete (stop_reason): streaming + typing off, error unchanged. */
    fun onTurnVisuallyComplete()

    /**
     * A transient, will-reconnect disconnect. Error clears; streaming/typing
     * are held on while [hasActiveSend] so the indicator survives the blip.
     */
    fun onTransientDisconnect(hasActiveSend: Boolean)

    /** A terminal disconnect failed the active turn: error set, streaming + typing off. */
    fun onDisconnectFailure(error: String)
}
