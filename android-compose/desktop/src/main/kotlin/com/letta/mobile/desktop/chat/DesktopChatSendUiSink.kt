package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.send.ChatSendUiSink

/**
 * The slice of controller state the shared send coordinator is allowed to move.
 *
 * Desktop tracks "is a turn running" as a conversation id rather than a boolean, so that a reply
 * landing in one conversation cannot clear the indicator in another. The sink translates the
 * coordinator's boolean-shaped vocabulary onto that, which is the whole reason this seam exists.
 */
internal interface DesktopChatSendSurface {
    fun currentError(): String?
    fun setError(message: String?)

    fun streamingConversationId(): String?
    fun thinkingConversationId(): String?

    /** Null stops the indicator; a conversation id starts or moves it. */
    fun setStreaming(conversationId: String?)
    fun setThinking(conversationId: String?)

    /** The conversation the user is looking at, used when an event names none. */
    fun selectedConversationId(): String?

    /**
     * Releases the composer gates raised when the send began. On this route the send call returns
     * long before the turn does, so only the lifecycle knows when the send is resolved.
     */
    fun settleSend(failed: Boolean)
}

/**
 * Maps [ChatSendUiSink] onto desktop's state container. Every callback is a semantic statement
 * about the turn, not a state diff, so the same shared orchestration drives Android's Compose state
 * and this without either platform reimplementing the lifecycle.
 */
internal class DesktopChatSendUiSink(
    private val surface: DesktopChatSendSurface,
) : ChatSendUiSink {

    override fun currentError(): String? = surface.currentError()

    override fun isStreaming(): Boolean = surface.streamingConversationId() != null

    override fun isAgentTyping(): Boolean = surface.thinkingConversationId() != null

    override fun onSendDispatched(conversationId: String?) {
        // A dispatch without a conversation id is a send into the conversation already on screen;
        // falling back to the selection keeps the indicator attached to something real rather than
        // starting a turn the UI cannot locate.
        beginTurn(conversationId ?: surface.selectedConversationId())
    }

    override fun onSendQueued(conversationId: String) = beginTurn(conversationId)

    override fun onSendFailed(message: String) {
        endTurn(failed = true)
        surface.setError(message)
    }

    /** Deliberately does not touch the indicator: an error can arrive mid-turn and the turn goes on. */
    override fun onError(message: String?) = surface.setError(message)

    override fun onTurnStarted(conversationId: String) = beginTurn(conversationId)

    override fun onMessageDelta(conversationId: String) = beginTurn(conversationId)

    /**
     * Token accounting is surfaced by the composer's own context-usage reader, which asks the
     * runtime directly. Recording it a second time here would give the composer two sources.
     */
    override fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int) = Unit

    override fun onTurnFinished(error: String?) {
        endTurn(failed = error != null)
        surface.setError(error)
    }

    /**
     * The turn is visually done but not yet resolved, so the error is left exactly as it stands:
     * a stop reason must not erase a failure the turn already reported.
     */
    override fun onTurnVisuallyComplete() = endTurn()

    override fun onTransientDisconnect(hasActiveSend: Boolean) {
        surface.setError(null)
        // A reconnect that will succeed is not the end of the turn. Dropping the indicator here
        // made a brief blip look like a dead turn, and the reply then arrived with no warning.
        if (!hasActiveSend) endTurn()
    }

    override fun onDisconnectFailure(error: String) {
        endTurn(failed = true)
        surface.setError(error)
    }

    private fun beginTurn(conversationId: String?) {
        if (conversationId == null) return
        surface.setStreaming(conversationId)
        surface.setThinking(conversationId)
        surface.setError(null)
    }

    private fun endTurn(failed: Boolean = false) {
        surface.setStreaming(null)
        surface.setThinking(null)
        // Stopping the indicators is not the same as resolving the send. The composer reads its own
        // gates, and leaving those raised is what made the transcript accept exactly one message.
        surface.settleSend(failed)
    }
}
