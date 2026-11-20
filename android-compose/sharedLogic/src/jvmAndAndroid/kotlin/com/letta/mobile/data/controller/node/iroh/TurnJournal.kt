package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

/**
 * The host's durable record of every turn's events, kept per conversation. [NodeTurnHost] appends to it
 * as a turn runs. An initiator that reconnects and re-sends its input is served the tail it missed from
 * it, also after the wrapper restarted; `feed.subscribe` serves the same data to any authorized viewer.
 * The implementation lives in the `feed` package, which depends on this one and not the other way round.
 *
 * Calls come from turn collectors and must not throw: a journal that cannot write degrades to memory and
 * says so through telemetry, it never fails a turn.
 */
interface TurnJournal {
    /** The writer for turn [turn] (its client message id) of [conversation], owned by [agent]. Nothing is stored until the turn's first event. */
    fun openTurn(agent: AgentId, conversation: ConversationId, turn: String): TurnJournalWriter

    /** Whether the journal holds turn [turn] of [conversation], owned by [agent]: a re-sent input for it must not run again. */
    fun knowsTurn(agent: AgentId, conversation: ConversationId, turn: String): Boolean

    /**
     * What an initiator that missed the end of turn [turn] needs, or null when the turn is unknown, still running,
     * was fully delivered, or was already replayed. The caller confirms a successful write with [InitiatorReplay.delivered].
     */
    fun replayForInitiator(agent: AgentId, conversation: ConversationId, turn: String): InitiatorReplay?
}

/** The journal's view of one turn. */
interface TurnJournalWriter {
    /** Records one delta body (its JSON text) as the turn's next event. The first one opens the turn: from then a re-sent input for it is a duplicate, also after a restart. */
    fun append(deltaJson: String)

    /** The turn's initiator stopped receiving: a redial re-sending the input must be served the tail. */
    fun noteInitiatorMissedTail()

    /**
     * The host stopped observing the turn. [terminalObserved] says whether a real terminal went through it; the
     * journal records that and invents no terminal of its own.
     */
    fun close(terminalObserved: Boolean)
}

/**
 * The tail an initiator missed. [deltas] are delta body JSON texts in order. [terminalObserved] is false when the turn
 * ended without a real terminal: only then does the legacy redial path add its own stub for old clients, and the
 * journal never stores that stub.
 */
class InitiatorReplay(
    val deltas: List<String>,
    val terminalObserved: Boolean,
    private val onDelivered: () -> Unit,
) {
    /** The replay reached the initiator; it is not offered again. */
    fun delivered() = onDelivered()
}

/** The journal of a host built without one: nothing is stored, no redial replay is offered. */
object NoTurnJournal : TurnJournal, TurnJournalWriter {
    override fun openTurn(agent: AgentId, conversation: ConversationId, turn: String): TurnJournalWriter = this

    override fun knowsTurn(agent: AgentId, conversation: ConversationId, turn: String): Boolean = false

    override fun replayForInitiator(agent: AgentId, conversation: ConversationId, turn: String): InitiatorReplay? = null

    override fun append(deltaJson: String) = Unit

    override fun noteInitiatorMissedTail() = Unit

    override fun close(terminalObserved: Boolean) = Unit
}
