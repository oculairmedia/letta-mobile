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
 * Its run is identified positively, never by elimination:
 * 1. the confirmed user row carries the run id of the run it started; or
 * 2. a run was seen to start after the utterance appeared (the session was idle with the utterance
 *    present, then a run went in flight), and the run is the first run id that appears after the
 *    utterance and was not visible anywhere before that start.
 *
 * Until one of these holds, no assistant text is spoken: text of an older or concurrent run is held
 * back rather than guessed at. Assistant rows without a run id are never attributed. Limitation:
 * the chat session exposes no direct link from an assistant row to the user message that caused
 * it, nor the in-flight run's id; when the confirmed user row carries no run id and a late row of
 * an older run first appears after the new run started, rule 2 can still attribute it wrongly.
 *
 * Not thread-safe: one tracker serves one reply's collection.
 */
internal class ChatReplyTracker(start: ChatUiState) {
    private val baseline: Set<String> = start.messages.flatMapTo(HashSet()) { listOfNotNull(it.id, it.clientMessageId) }
    private val seenBeforeStart: MutableSet<String> = start.messages.mapNotNullTo(HashSet()) { it.runId }
    private val agent: String? = start.agentId
    private val spokenLengths = mutableMapOf<String, Int>()
    private var anchor: AnchorIdentity? = null
    private var ownRun: String? = null
    private var idleSinceAnchor = false
    private var runStarted = false

    /** The utterance's own run is identified, or a run started after it and has already ended. */
    fun delivered(state: ChatUiState): Boolean {
        if (observe(state) < 0) return false
        return when {
            ownRun != null -> true
            runStarted -> !state.isRunInFlight
            else -> false
        }
    }

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

    /** Updates what is known about the utterance's run from [state]; returns the anchor's index or -1. */
    private fun observe(state: ChatUiState): Int {
        val index = anchorIndex(state)
        if (index < 0) return index
        state.messages[index].runId?.let { ownRun = it }
        if (!runStarted) watchForStart(state)
        if (ownRun == null && runStarted) ownRun = firstNewRun(state, index)
        return index
    }

    /** Records every run visible before the utterance's run starts, and detects that start. */
    private fun watchForStart(state: ChatUiState) {
        if (state.isRunInFlight && idleSinceAnchor) {
            runStarted = true
            return
        }
        state.messages.mapNotNullTo(seenBeforeStart) { it.runId }
        if (!state.isRunInFlight) idleSinceAnchor = true
    }

    private fun firstNewRun(state: ChatUiState, index: Int): String? =
        state.messages.subList(index + 1, state.messages.size)
            .firstNotNullOfOrNull { row -> row.runId?.takeIf { it !in seenBeforeStart } }

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
