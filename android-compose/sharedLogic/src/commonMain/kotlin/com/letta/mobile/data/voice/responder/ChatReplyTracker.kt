package com.letta.mobile.data.voice.responder

import com.letta.mobile.data.voice.VoiceText

/**
 * Follows the run that answers one sent message, by identity only.
 *
 * The message is [sent]; its run is whatever the session publishes in
 * [VoiceChatState.startedRuns] for that id (every provisional and durable id of the run). That
 * mapping is the single source of attribution: a row belongs to the reply only when its run id is
 * one of those ids. The confirmed user row's own run id is not used as a second source, because
 * the session publishes the pairing at the moment the run starts, which is earlier and does not
 * depend on how rows are confirmed. Nothing is inferred from text, position or timing. Until the
 * session publishes the run, nothing is spoken; if it never does, the responder fails as
 * UNATTRIBUTED.
 *
 * Not thread-safe: one tracker serves one reply's collection.
 */
internal class ChatReplyTracker(start: VoiceChatState, private val sent: ClientMessageId) {
    private val conversation: VoiceConversationKey? = start.conversation
    private val spokenLengths = mutableMapOf<VoiceRowKey, Int>()
    private val endedParts = mutableSetOf<VoiceRowKey>()

    /** The sent message's own row has appeared in the conversation. */
    fun anchored(state: VoiceChatState): Boolean = state.rows.any { it.clientMessageId == sent }

    /** The run the message started is published. */
    fun delivered(state: VoiceChatState): Boolean = ownRuns(state).isNotEmpty()

    /** Delivery is decided either way: delivered, or the conversation changed. */
    fun settled(state: VoiceChatState): Boolean = delivered(state) || conversationChanged(state)

    /** The session moved to another conversation. */
    fun conversationChanged(state: VoiceChatState): Boolean = conversation != null && state.conversation != conversation

    /** The message is still in the chat's send queue behind a run. */
    fun queued(state: VoiceChatState): Boolean = sent in state.queued

    /** The message's run is still in flight. */
    fun running(state: VoiceChatState): Boolean = ownRuns(state).any { it in state.activeRuns }

    /** The message's run failed. */
    fun failed(state: VoiceChatState): Boolean {
        val runs = ownRuns(state)
        return runs.any { it in state.failedRuns } || ownRows(state, runs).any { it.kind == VoiceRowKind.ERROR }
    }

    /** The run's assistant text added since the last call, or null when nothing new arrived. */
    fun advance(state: VoiceChatState): VoiceText? {
        val texts = ownRows(state, ownRuns(state)).filter { it.kind == VoiceRowKind.ASSISTANT_TEXT }
        val pieces = texts.mapNotNull(::newText)
        return if (pieces.isEmpty()) null else VoiceText(pieces.joinToString(""))
    }

    /**
     * True, once per assistant text row already spoken from, when a later row of the run follows it
     * (a tool call, reasoning, another message): that row's text is complete, so its last sentence
     * can be spoken now rather than when the run ends.
     */
    fun partEnded(state: VoiceChatState): Boolean {
        val rows = ownRows(state, ownRuns(state))
        val ended = rows.dropLast(1).filter { it.key in spokenLengths && it.key !in endedParts }
        endedParts += ended.map { it.key }
        return ended.isNotEmpty()
    }

    private fun ownRuns(state: VoiceChatState): Set<VoiceRunId> = state.startedRuns[sent].orEmpty()

    private fun ownRows(state: VoiceChatState, runs: Set<VoiceRunId>): List<VoiceChatRow> =
        if (runs.isEmpty()) emptyList() else state.rows.filter { row -> row.runId?.let { it in runs } == true }

    private fun newText(row: VoiceChatRow): String? {
        val spoken = spokenLengths[row.key]
        val content = row.text.value
        if (content.length <= (spoken ?: 0)) return null
        val separator = if (spoken == null && spokenLengths.isNotEmpty()) MESSAGE_SEPARATOR else ""
        spokenLengths[row.key] = content.length
        return separator + content.substring(spoken ?: 0)
    }

    private companion object {
        const val MESSAGE_SEPARATOR = "\n"
    }
}
