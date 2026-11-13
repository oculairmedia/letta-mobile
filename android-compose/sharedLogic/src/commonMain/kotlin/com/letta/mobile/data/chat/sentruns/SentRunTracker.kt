package com.letta.mobile.data.chat.sentruns

import kotlin.concurrent.Volatile

/** A turn id and a run id as one frame or one turn state names them; either may be unknown. */
internal data class SentRunTail(val turnId: String?, val runId: String?)

/**
 * The run one conversation's own send started, as that conversation's turn state sees it, written
 * to the shared [ledger] by identity only.
 *
 * The send's echo pairs its otid with a run ([bind]); a later id the same turn takes joins it
 * ([alias]); the state leaving that turn ([leave]) or the turn settling ([end]) ends it. Driven
 * under the send pipeline's lifecycle mutex; the fields are volatile for readers on other threads.
 */
internal class SentRunTracker(private val ledger: SentRunLedger) {
    @Volatile private var otid: String? = null
    @Volatile private var turnId: String? = null

    /**
     * The echo of the send behind [sent] named [echo]'s run. [current] is the state's own turn and
     * run: its run id joins too when it is the echo's turn.
     */
    fun bind(sent: String, echo: SentRunTail, current: SentRunTail) {
        val run = echo.runId?.takeIf { it.isNotBlank() } ?: return
        val turn = echo.turnId?.takeIf { it.isNotBlank() }
        if (otid != sent) {
            end(failed = false)
            otid = sent
        }
        if (turnId == null) turnId = turn
        val sameTurnRun = current.runId?.takeIf { it.isNotBlank() && turn != null && turn == current.turnId }
        ledger.started(SentMessageId(sent), setOfNotNull(run, sameTurnRun).mapTo(mutableSetOf(), ::SentRunId))
    }

    /** The send behind [sent] was accepted: rows the server stamps with it as their turn are its run's. */
    fun accepted(sent: String) {
        ledger.sent(SentMessageId(sent))
    }

    /**
     * A live row of [turn] carried [run]: it joins the own run when [turn] is the send's own id or
     * the turn its echo named.
     */
    fun row(turn: String?, run: String?) {
        ledger.row(turn, run)
        val sent = otid ?: return
        val own = turnId ?: return
        if (turn != own || run.isNullOrBlank()) return
        ledger.started(SentMessageId(sent), setOf(SentRunId(run)))
    }

    /** The state took [run] while on [currentTurn]: an id of the own run's turn joins its entry. */
    fun alias(run: String?, currentTurn: String?) {
        val sent = otid ?: return
        val turn = turnId ?: return
        if (run.isNullOrBlank() || turn != currentTurn) return
        ledger.started(SentMessageId(sent), setOf(SentRunId(run)))
    }

    /** The state moves from [currentTurn] to [nextTurn]: leaving the own run's turn ends that run. */
    fun leave(currentTurn: String?, nextTurn: String?) {
        val turn = turnId ?: return
        if (currentTurn == turn && nextTurn != null && nextTurn != turn) end(failed = false)
    }

    /** A send other than [sent] is accepted: an own run still marked in flight is over. */
    fun endUnless(sent: String) {
        if (otid != sent) end(failed = false)
    }

    /**
     * The turn known by [runIds] settled, as a failure when [failed]: remembered for a late echo,
     * and the own run, if one is in flight, ends.
     */
    fun settle(runIds: Set<String?>, failed: Boolean) {
        val ids = runIds.filterNot { it.isNullOrBlank() }.mapTo(mutableSetOf()) { SentRunId(it.orEmpty()) }
        ledger.settled(ids, failed)
        end(failed)
    }

    /** The own run ended, as a failure when [failed]; nothing when no own run is in flight. */
    fun end(failed: Boolean) {
        val sent = otid ?: return
        otid = null
        turnId = null
        ledger.ended(SentMessageId(sent), failed)
    }
}
