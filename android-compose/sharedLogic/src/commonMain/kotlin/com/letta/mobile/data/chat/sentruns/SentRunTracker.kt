package com.letta.mobile.data.chat.sentruns

import kotlin.concurrent.Volatile

/** A turn id and a run id as one frame or one turn state names them; either may be unknown. */
internal data class SentRunTail(val turnId: String?, val runId: String?)

/** A turn state moving from turn [from] to turn [to]; either may be unknown. */
internal data class SentTurnMove(val from: String?, val to: String?)

/** A turn that settled: every run id it is known by, and whether it ended as a failure. */
internal data class SettledSentTurn(val runIds: Set<String?>, val failed: Boolean)

/**
 * The run one conversation's own send started, as that conversation's turn state sees it, written
 * to the shared [ledger] by identity only.
 *
 * The send's echo pairs its otid with a run ([bind]); a later id the same turn takes joins it
 * ([alias], [row]); the state leaving that turn ([leave]) or the turn settling ([settle]) ends it.
 * When the echo names no turn, the own run's turn is the one a frame names together with a run
 * the send already started. Driven under the send pipeline's lifecycle mutex; the fields are
 * volatile for readers on other threads.
 */
internal class SentRunTracker(private val ledger: SentRunLedger) {
    @Volatile private var otid: SentMessageId? = null
    @Volatile private var turnId: String? = null

    /**
     * The echo of the send [sent] named [echo]'s run. [current] is the state's own turn and run:
     * its run id joins too when it is on the echo's turn.
     */
    fun bind(sent: SentMessageId, echo: SentRunTail, current: SentRunTail) {
        val run = echo.runId?.takeIf { it.isNotBlank() } ?: return
        if (otid != sent) {
            end()
            otid = sent
        }
        val turn = turnOf(echo, current)
        if (turnId == null) turnId = turn
        val sameTurnRun = current.runId?.takeIf { it.isNotBlank() && turn != null && turn == current.turnId }
        ledger.started(sent, setOfNotNull(run, sameTurnRun).mapTo(mutableSetOf(), ::SentRunId))
    }

    /** The turn [echo] names, or, when it names none, [current]'s turn when that is on the echo's run. */
    private fun turnOf(echo: SentRunTail, current: SentRunTail): String? =
        echo.turnId?.takeIf { it.isNotBlank() }
            ?: current.turnId?.takeIf { it.isNotBlank() && current.runId == echo.runId }

    /** The send [sent] was accepted: rows the server stamps with it as their turn are its run's. */
    fun accepted(sent: SentMessageId) {
        ledger.sent(sent)
    }

    /** A live [row] named its turn and run: the run joins the own run when the row is on its turn. */
    fun row(row: SentRunTail) {
        ledger.row(row.turnId, row.runId)
        join(row)
    }

    /** The state took [current]'s run while on [current]'s turn: an id of the own run's turn joins its entry. */
    fun alias(current: SentRunTail) {
        join(current)
    }

    /** [tail]'s run joins the own run's entry when [tail] is on the own run's turn. */
    private fun join(tail: SentRunTail) {
        val sent = otid ?: return
        adoptTurn(sent, tail)
        val run = tail.runId?.takeIf { it.isNotBlank() } ?: return
        if (turnId == null || tail.turnId != turnId) return
        ledger.started(sent, setOf(SentRunId(run)))
    }

    /** While the own run's turn is unknown, [tail]'s turn is it when [tail]'s run is one [sent] started. */
    private fun adoptTurn(sent: SentMessageId, tail: SentRunTail) {
        if (turnId != null) return
        val run = tail.runId?.let(::SentRunId) ?: return
        if (run in ledger.state.value.started[sent].orEmpty()) turnId = tail.turnId?.takeIf { it.isNotBlank() }
    }

    /** The state makes [move]: leaving the own run's turn for another turn ends that run. */
    fun leave(move: SentTurnMove) {
        val turn = turnId ?: return
        if (move.from == turn && move.to != null && move.to != turn) end()
    }

    /** A send other than [sent] is accepted: an own run still marked in flight is over. */
    fun endUnless(sent: SentMessageId) {
        if (otid != sent) end()
    }

    /** [turn] settled: its runs are remembered for a late echo, and the own run, if one is in flight, ends with it. */
    fun settle(turn: SettledSentTurn) {
        val ids = turn.runIds.filterNot { it.isNullOrBlank() }.mapTo(mutableSetOf()) { SentRunId(it.orEmpty()) }
        ledger.settled(ids, turn.failed)
        if (turn.failed) fail() else end()
    }

    /** The own run ended; nothing when no own run is in flight. */
    fun end() {
        release()?.let { ledger.ended(it, failed = false) }
    }

    /** The own run ended as a failure; nothing when no own run is in flight. */
    fun fail() {
        release()?.let { ledger.ended(it, failed = true) }
    }

    /** Forgets the own run and returns the send that started it, or null when none is in flight. */
    private fun release(): SentMessageId? {
        val sent = otid ?: return null
        otid = null
        turnId = null
        return sent
    }
}
