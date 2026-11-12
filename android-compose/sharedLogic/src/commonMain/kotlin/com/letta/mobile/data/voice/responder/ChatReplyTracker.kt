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
 * or client message id (a pending row's id changes when the server confirms it). Runs seen before
 * the send, or in rows before the anchor, are earlier runs; their late rows are never spoken.
 *
 * The session-wide in-flight flag is trusted only once the utterance's own run is evident: a row
 * of a new run after the anchor, or a run starting after the session was idle with the anchor
 * present. A confirmed anchor with nothing in flight means the run already ended (perhaps with no
 * text). Not thread-safe: one tracker serves one reply's collection.
 */
internal class ChatReplyTracker(start: ChatUiState) {
    private val baseline: Set<String> = start.messages.flatMapTo(HashSet()) { listOfNotNull(it.id, it.clientMessageId) }
    private val earlierRuns: MutableSet<String> = start.messages.mapNotNullTo(HashSet()) { it.runId }
    private val agent: String? = start.agentId
    private val spokenLengths = mutableMapOf<String, Int>()
    private var anchor: AnchorIdentity? = null
    private var run: String? = null
    private var idleSinceAnchor = false
    private var ownRunSeen = false

    /** The utterance's own run has started, or already ended. */
    fun delivered(state: ChatUiState): Boolean {
        val index = observe(state)
        return when {
            index < 0 -> false
            ownRunSeen -> true
            else -> !state.messages[index].isPending && !state.isRunInFlight
        }
    }

    /** Delivery is decided either way: delivered, or the conversation moved to another agent. */
    fun settled(state: ChatUiState): Boolean = delivered(state) || agentChanged(state)

    /** The conversation now belongs to another agent. */
    fun agentChanged(state: ChatUiState): Boolean = agent != null && state.agentId != agent

    /** The run failed: the session says so, or an error row follows the utterance. */
    fun failed(state: ChatUiState): Boolean = state.runFailed || afterAnchor(state).any { it.isError }

    /** The assistant text added since the last call, or null when nothing new arrived. */
    fun advance(state: ChatUiState): VoiceText? {
        observe(state)
        val pieces = replies(state).mapNotNull(::newText)
        return if (pieces.isEmpty()) null else VoiceText(pieces.joinToString(""))
    }

    /** Updates what is known about the utterance's run from [state]; returns the anchor's index or -1. */
    private fun observe(state: ChatUiState): Int {
        val index = anchorIndex(state)
        if (index < 0) return index
        state.messages.subList(0, index).mapNotNullTo(earlierRuns) { it.runId }
        if (afterAnchor(state).any { it.isOwnRun() }) ownRunSeen = true
        if (!state.isRunInFlight) idleSinceAnchor = true
        if (state.isRunInFlight && idleSinceAnchor) ownRunSeen = true
        return index
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
        val texts = afterAnchor(state).filter { it.isSpokenText() && it.isOwnRun() }
        if (run == null) run = texts.firstOrNull()?.runId
        return texts.filter { it.belongsTo(run) }
    }

    private fun UiMessage.isOwnRun(): Boolean = runId?.let { it !in earlierRuns } ?: false

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
