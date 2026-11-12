package com.letta.mobile.data.voice.responder

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.ui.chat.render.ChatUiState

/** The sent utterance's row, by row id and by client message id (which survives confirmation). */
private data class AnchorIdentity(val rowId: String, val clientRow: String?) {
    fun matches(message: UiMessage): Boolean = message.id == rowId || message.clientMessageId == (clientRow ?: rowId)
}

/**
 * Follows the run that answers one sent utterance, in successive chat states.
 *
 * The utterance is anchored as the first user row that was not in [start], matched later by row id
 * or client message id (a pending row's id changes when the server confirms it).
 *
 * Requirement: the session must expose the run started by a sent message. Today the only such
 * link in the chat state is the run id on the confirmed user row, so that is the only way a run is
 * attributed: an assistant row is the utterance's only when its run id equals the confirmed user
 * row's run id. Nothing is inferred from timing, ordering or which runs were seen before. Until the
 * link exists nothing is spoken; if it never arrives the responder fails as UNATTRIBUTED rather
 * than speak another run's text. Live rows streamed under a provisional run id are not matched
 * until the session gives them the durable run id.
 *
 * Not thread-safe: one tracker serves one reply's collection.
 */
internal class ChatReplyTracker(start: ChatUiState) {
    private val baseline: Set<String> = start.messages.flatMapTo(HashSet()) { listOfNotNull(it.id, it.clientMessageId) }
    private val agent: String? = start.agentId
    private val spokenLengths = mutableMapOf<String, Int>()
    private var anchor: AnchorIdentity? = null
    private var ownRun: String? = null

    /** The utterance has appeared in the conversation (whether or not its run is known). */
    val anchored: Boolean get() = anchor != null

    /** The run the utterance started is positively identified. */
    fun delivered(state: ChatUiState): Boolean = observe(state) >= 0 && ownRun != null

    /** Delivery is decided either way: delivered, or the conversation moved to another agent. */
    fun settled(state: ChatUiState): Boolean = delivered(state) || agentChanged(state)

    /** The conversation now belongs to another agent. */
    fun agentChanged(state: ChatUiState): Boolean = agent != null && state.agentId != agent

    /** The run failed: the session says so, or an error row of the utterance's run follows it. */
    fun failed(state: ChatUiState): Boolean = state.runFailed || ownRows(state).any { it.isError }

    /** The utterance's run's assistant text added since the last call, or null when nothing new arrived. */
    fun advance(state: ChatUiState): VoiceText? {
        observe(state)
        val pieces = ownRows(state).filter { it.isSpokenText() }.mapNotNull(::newText)
        return if (pieces.isEmpty()) null else VoiceText(pieces.joinToString(""))
    }

    /** Records the utterance's run from its confirmed row; returns the anchor's index or -1. */
    private fun observe(state: ChatUiState): Int {
        val index = anchorIndex(state)
        if (index >= 0) state.messages[index].runId?.let { ownRun = it }
        return index
    }

    private fun ownRows(state: ChatUiState): List<UiMessage> {
        val run = ownRun ?: return emptyList()
        val index = anchorIndex(state)
        if (index < 0) return emptyList()
        return state.messages.subList(index + 1, state.messages.size).filter { it.runId == run }
    }

    private fun newText(message: UiMessage): String? {
        val spoken = spokenLengths[message.id]
        val content = message.content
        if (content.length <= (spoken ?: 0)) return null
        val separator = if (spoken == null && spokenLengths.isNotEmpty()) MESSAGE_SEPARATOR else ""
        spokenLengths[message.id] = content.length
        return separator + content.substring(spoken ?: 0)
    }

    private fun anchorIndex(state: ChatUiState): Int {
        anchor?.let { known -> return state.messages.indexOfFirst(known::matches) }
        val index = state.messages.indexOfFirst { it.role == USER_ROLE && it.isNew() }
        if (index >= 0) anchor = state.messages[index].let { AnchorIdentity(it.id, it.clientMessageId) }
        return index
    }

    private fun UiMessage.isNew(): Boolean = id !in baseline && (clientMessageId ?: id) !in baseline

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
