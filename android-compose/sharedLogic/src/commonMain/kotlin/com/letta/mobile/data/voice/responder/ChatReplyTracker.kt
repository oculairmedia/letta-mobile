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
 * Requirement: the session must expose the identity of a sent message and the run it started.
 * The chat port offers neither today: `sendText` returns no client message id, and the chat state
 * publishes no run per sent message. So this tracker fails closed:
 *
 * - The utterance's row is a user row absent from [start] whose text equals [text]. Text is not
 *   identity, so it is only a necessary condition: if more than one new row could be the utterance
 *   the reply is [ambiguous] and nothing is spoken. Once chosen, the row is followed by row id or
 *   client message id (a pending row's id changes when the server confirms it).
 * - The run is the run id on that row once it is confirmed. A pending (local) row's run id is
 *   ignored: local rows are built without one, and only the server's confirmation names the run.
 * - An assistant row is the utterance's only when its run id equals that run id. Nothing is
 *   inferred from timing, ordering or which runs were seen before; rows without a run id, and
 *   live rows streamed under a provisional run id, are not attributed.
 *
 * Until the run is known nothing is spoken; if it never becomes known the responder fails as
 * UNATTRIBUTED rather than speak another message's reply.
 *
 * Not thread-safe: one tracker serves one reply's collection.
 */
internal class ChatReplyTracker(start: ChatUiState, private val text: VoiceText) {
    private val baseline: Set<String> = start.messages.flatMapTo(HashSet()) { listOfNotNull(it.id, it.clientMessageId) }
    private val agent: String? = start.agentId
    private val spokenLengths = mutableMapOf<String, Int>()
    private var anchor: AnchorIdentity? = null
    private var ownRun: String? = null

    /** The utterance has appeared in the conversation (whether or not its run is known). */
    val anchored: Boolean get() = anchor != null

    /** More than one new message could be the utterance; the reply cannot be attributed. */
    var ambiguous: Boolean = false
        private set

    /** The run the utterance started is positively identified. */
    fun delivered(state: ChatUiState): Boolean = observe(state) >= 0 && ownRun != null

    /** Delivery is decided either way: delivered, ambiguous, or the conversation moved to another agent. */
    fun settled(state: ChatUiState): Boolean = when {
        delivered(state) -> true
        ambiguous -> true
        else -> agentChanged(state)
    }

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
        if (index < 0) return index
        val row = state.messages[index]
        if (!row.isPending) row.runId?.let { ownRun = it }
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
        val candidates = state.messages.withIndex().filter { (_, row) -> row.couldBeUtterance() }
        if (candidates.size > 1) ambiguous = true
        if (ambiguous) return -1
        anchor?.let { known -> return state.messages.indexOfFirst(known::matches) }
        val chosen = candidates.singleOrNull() ?: return -1
        anchor = AnchorIdentity(chosen.value.id, chosen.value.clientMessageId)
        return chosen.index
    }

    /** A new user row whose text equals the utterance: necessary for being it, not sufficient. */
    private fun UiMessage.couldBeUtterance(): Boolean = when {
        role != USER_ROLE -> false
        !isNew() -> false
        else -> content.trim() == text.value.trim()
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
