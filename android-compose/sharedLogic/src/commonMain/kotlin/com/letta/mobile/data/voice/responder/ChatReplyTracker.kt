package com.letta.mobile.data.voice.responder

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.ui.chat.render.ChatUiState

/**
 * Follows one run's assistant text in successive chat states. The utterance is anchored as the
 * first user message absent from [start]; the reply is the assistant text after it, from the run
 * of the first such message. Not thread-safe: one tracker serves one reply's collection.
 */
internal class ChatReplyTracker(start: ChatUiState) {
    private val baseline: Set<String> = start.messages.mapTo(HashSet()) { it.id }
    private val agent: String? = start.agentId
    private val spokenLengths = mutableMapOf<String, Int>()
    private var anchor: String? = null
    private var run: String? = null

    /** The utterance is in the conversation and its run has started (or already answered). */
    fun delivered(state: ChatUiState): Boolean {
        if (anchorIndex(state) < 0) return false
        return state.isRunInFlight || replies(state).isNotEmpty()
    }

    /** The conversation now belongs to another agent. */
    fun agentChanged(state: ChatUiState): Boolean = agent != null && state.agentId != agent

    /** The run failed: the session says so, or an error row follows the utterance. */
    fun failed(state: ChatUiState): Boolean = state.runFailed || afterAnchor(state).any { it.isError }

    /** The assistant text added since the last call, or null when nothing new arrived. */
    fun advance(state: ChatUiState): VoiceText? {
        val pieces = replies(state).mapNotNull(::newText)
        return if (pieces.isEmpty()) null else VoiceText(pieces.joinToString(""))
    }

    private fun newText(message: UiMessage): String? {
        val spoken = spokenLengths[message.id]
        val content = message.content
        if (content.length <= (spoken ?: 0)) return null
        val separator = if (spoken == null && spokenLengths.isNotEmpty()) MESSAGE_SEPARATOR else ""
        spokenLengths[message.id] = content.length
        return separator + content.substring(spoken ?: 0)
    }

    private fun replies(state: ChatUiState): List<UiMessage> {
        val texts = afterAnchor(state).filter { it.isSpokenText() }
        if (run == null) run = texts.firstOrNull()?.runId
        return texts.filter { it.belongsTo(run) }
    }

    private fun UiMessage.belongsTo(expected: String?): Boolean = when {
        runId == null -> true
        expected == null -> true
        else -> runId == expected
    }

    private fun afterAnchor(state: ChatUiState): List<UiMessage> {
        val index = anchorIndex(state)
        return if (index < 0) emptyList() else state.messages.subList(index + 1, state.messages.size)
    }

    private fun anchorIndex(state: ChatUiState): Int {
        val known = anchor
        if (known != null) return state.messages.indexOfFirst { it.id == known }
        val index = state.messages.indexOfFirst { it.role == USER_ROLE && it.id !in baseline }
        if (index >= 0) anchor = state.messages[index].id
        return index
    }

    private fun UiMessage.isSpokenText(): Boolean = when {
        role != ASSISTANT_ROLE -> false
        isReasoning || isError -> false
        isCompaction -> false
        else -> toolCalls.isNullOrEmpty()
    }

    private companion object {
        const val USER_ROLE = "user"
        const val ASSISTANT_ROLE = "assistant"
        const val MESSAGE_SEPARATOR = "\n"
    }
}
