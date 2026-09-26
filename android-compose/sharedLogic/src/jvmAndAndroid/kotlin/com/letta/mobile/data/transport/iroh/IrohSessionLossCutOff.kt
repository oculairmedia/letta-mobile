package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * letta-mobile-qygvv.16: ends every turn whose transport session was lost (connection closed,
 * redial, wrapper restart) within a bounded time, with exactly one terminal.
 *
 * Before, closing the session cancelled each turn's send job at once. The engine's own terminal
 * could never flow out of a cancelled job, nothing else produced one, and the phone sat on
 * "Thinking…" until the app was restarted.
 *
 * Now each non-terminal turn keeps its send job for up to [graceMs]: the engine sees its stream
 * detach and ends the turn through its authoritative terminal path, which flows out through the
 * still-live job. Only then is the job cancelled. A turn that still has no terminal (the engine
 * never started, or its job was already stuck) gets a synthetic failed terminal through the same
 * exactly-once claim, so a real terminal that won the race is never duplicated.
 */
internal class IrohSessionLossCutOff(
    private val scope: CoroutineScope,
    private val registry: IrohTurnRegistry,
    private val emitBoth: suspend (ServerFrame) -> Unit,
    private val graceMs: Long = SESSION_LOSS_TERMINAL_GRACE_MS,
) {
    /** Cuts off every registered send job of the closing session. */
    fun cutOff(reason: String) {
        registry.allSendJobEntries().forEach { registration -> cutOffOne(registration, reason) }
    }

    private fun cutOffOne(registration: IrohSendJobRegistration, reason: String) {
        val job = registry.removeSendJob(registration.conversationId) ?: return
        val turn = registry.getActiveTurn(registration.conversationId)
        if (turn == null || turn.hasTerminal) {
            job.cancel()
            return
        }
        turn.markCutOffBySessionLoss()
        scope.launch { awaitTerminalThenCancel(turn, job, reason) }
    }

    private suspend fun awaitTerminalThenCancel(turn: IrohActiveTurn, job: Job, reason: String) {
        val ownTerminal = withTimeoutOrNull(graceMs.milliseconds) { turn.terminalReached.await() }
        job.cancel()
        val outcome = when {
            ownTerminal != null -> CutOffOutcome.OwnTerminal
            publishSyntheticTerminal(turn) -> CutOffOutcome.SyntheticTerminal
            else -> CutOffOutcome.RacedTerminal
        }
        report(turn, reason, outcome)
    }

    private suspend fun publishSyntheticTerminal(turn: IrohActiveTurn): Boolean {
        val publication = IrohTerminalPublication(turn, IrohTerminalStatus(FAILED), IrohTerminalSource.SessionLost)
        if (!registry.claimTerminal(publication)) return false
        withContext(NonCancellable) {
            emitBoth(connectionLostError(turn))
            emitBoth(failedTurnDone(turn))
            registry.retireClaimed(publication)
        }
        return true
    }

    private fun report(turn: IrohActiveTurn, reason: String, outcome: CutOffOutcome) {
        Telemetry.event(
            "IrohTransport", "turn.cutoff_by_session_loss",
            "conversationId" to turn.conversationId,
            "turnId" to turn.turnId,
            "runId" to turn.runId,
            "reason" to reason,
            "terminal" to outcome.name,
            "terminalSource" to (turn.terminalSource?.name ?: ""),
            level = Telemetry.Level.WARN,
        )
    }

    private enum class CutOffOutcome { OwnTerminal, SyntheticTerminal, RacedTerminal }

    companion object {
        /** Far longer than the engine needs to publish its own terminal once its stream detaches. */
        const val SESSION_LOSS_TERMINAL_GRACE_MS = 2_000L
        private const val FAILED = "failed"

        private fun connectionLostError(turn: IrohActiveTurn) = ServerFrame.Error(
            id = IrohTransportSupport.frameId("error"),
            ts = IrohTransportSupport.nowIso(),
            code = TurnFailureNotices.CONNECTION_LOST_KIND,
            message = TurnFailureNotices.messageFor(TurnFailureNotices.CONNECTION_LOST_KIND),
            conversationId = turn.conversationId,
            turnId = turn.turnId,
            runId = turn.runId,
        )

        private fun failedTurnDone(turn: IrohActiveTurn) = ServerFrame.TurnDone(
            id = IrohTransportSupport.frameId("turn_done"),
            ts = IrohTransportSupport.nowIso(),
            turnId = turn.turnId,
            runId = turn.runId,
            status = FAILED,
        )
    }
}
