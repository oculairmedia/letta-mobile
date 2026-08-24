package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.api.RedialWhileTurnActive
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Unique owner token for a registered Iroh turn.
 */
data class IrohTurnToken(
    val conversationId: String,
    val generation: Long,
    val turnId: String,
)

/**
 * Per-turn client state shared between streaming send jobs, observer ingest, and cancel.
 */
class IrohActiveTurn(
    val token: IrohTurnToken,
    initialRunId: String,
    val agentId: String,
) {
    val conversationId: String get() = token.conversationId
    val generation: Long get() = token.generation
    val turnId: String get() = token.turnId

    private val runIdRef = atomic(initialRunId)
    private val terminalClaimed = atomic<String?>(null)

    /** Completes with the terminal status once the single terminal is emitted or retired. */
    val terminalReached = CompletableDeferred<String>()

    @Volatile
    var job: Job? = null

    /** The canonical run id — the real server run id once promoted. */
    val runId: String get() = runIdRef.value

    /**
     * Promote a still-synthetic run id to the real server run id. Returns true only
     * on the first real promotion.
     */
    fun promoteRunId(real: String): Boolean {
        if (real.isBlank() || real.isIrohSyntheticRunId()) return false
        while (true) {
            val current = runIdRef.value
            if (!current.isIrohSyntheticRunId() || current == real) return false
            if (runIdRef.compareAndSet(current, real)) return true
        }
    }

    /** Wins exactly once; the first terminal (server, observer, or synthetic cancel) claims it. */
    fun tryClaimTerminal(source: String): Boolean = terminalClaimed.compareAndSet(expect = null, update = source)
    fun claimTerminal(): Boolean = tryClaimTerminal("engine")
    val hasTerminal: Boolean get() = terminalClaimed.value != null
    val terminalSource: String? get() = terminalClaimed.value
}

/**
 * Result of trying to register a new turn on a conversation.
 */
sealed interface IrohTryStartResult {
    data class Started(val turn: IrohActiveTurn) : IrohTryStartResult
    data class Busy(val activeTurn: IrohActiveTurn, val rejectedTurnId: String) : IrohTryStartResult
}

/**
 * Atomic registry of active turns, send jobs, interrupted turns, and frame ownership
 * for [IrohChannelTransport].
 */
class IrohTurnRegistry {
    private val activeTurns = ConcurrentHashMap<String, IrohActiveTurn>()
    private val activeSendJobs = ConcurrentHashMap<String, Job>()
    private val frameOwnershipPath = ConcurrentHashMap<String, String>()
    private val recentlyRetiredRuns = ConcurrentHashMap<String, Long>()
    private val interruptedTurns = ConcurrentHashMap<String, RedialWhileTurnActive>()

    fun tryStart(
        token: IrohTurnToken,
        initialRunId: String,
        agentId: String,
    ): IrohTryStartResult {
        val newTurn = IrohActiveTurn(token, initialRunId, agentId)
        var busy: IrohActiveTurn? = null
        activeTurns.compute(token.conversationId) { _, existing ->
            if (existing != null && !existing.terminalReached.isCompleted) {
                busy = existing
                existing
            } else {
                newTurn
            }
        }
        return busy?.let { IrohTryStartResult.Busy(it, token.turnId) } ?: IrohTryStartResult.Started(newTurn)
    }


    fun registerSendJob(conversationId: String, job: Job) {
        activeSendJobs[conversationId] = job
    }

    fun unregisterSendJob(conversationId: String, job: Job): Boolean {
        return activeSendJobs.remove(conversationId, job)
    }

    fun removeSendJob(conversationId: String): Job? = activeSendJobs.remove(conversationId)
    fun getSendJob(conversationId: String): Job? = activeSendJobs[conversationId]

    fun getActiveTurn(conversationId: String): IrohActiveTurn? = activeTurns[conversationId]

    fun hasActiveTurn(conversationId: String): Boolean =
        activeTurns[conversationId]?.terminalReached?.isCompleted == false

    val hasAnyActiveTurn: Boolean
        get() = activeTurns.values.any { !it.terminalReached.isCompleted }

    fun concurrentTurns(excludingConversationId: String): List<IrohActiveTurn> =
        activeTurns.values.filter { it.conversationId != excludingConversationId && !it.hasTerminal }

    fun promoteRunId(conversationId: String, turnId: String, realRunId: String): Boolean {
        val turn = activeTurns[conversationId] ?: return false
        if (turn.turnId != turnId) return false
        return turn.promoteRunId(realRunId)
    }

    fun observeTerminal(conversationId: String, source: String): Boolean {
        val turn = activeTurns[conversationId] ?: return false
        return turn.tryClaimTerminal(source)
    }

    fun publishTerminal(turn: IrohActiveTurn, status: String, source: String): Boolean {
        if (!turn.tryClaimTerminal(source)) return false
        retire(turn, status, source)
        return true
    }

    fun retire(turn: IrohActiveTurn, status: String, source: String) {
        if (interruptedTurns[turn.conversationId]?.turnId == turn.turnId) {
            interruptedTurns.remove(turn.conversationId)
        }
        frameOwnershipPath.remove(turn.conversationId)
        if (turn.runId.isNotBlank()) {
            val now = System.currentTimeMillis()
            recentlyRetiredRuns.entries.removeIf { now - it.value > RETIRED_RUN_TTL_MS }
            recentlyRetiredRuns[turn.runId] = now
        }
        activeTurns.remove(turn.conversationId, turn)
        turn.terminalReached.complete(status)
    }

    fun finish(token: IrohTurnToken): Boolean {
        val turn = activeTurns[token.conversationId] ?: return false
        if (turn.token != token) return false
        val removed = activeTurns.remove(token.conversationId, turn)
        if (removed) {
            frameOwnershipPath.remove(token.conversationId)
        }
        return removed
    }

    fun isRetiredRun(runId: String): Boolean {
        val retiredAt = recentlyRetiredRuns[runId] ?: return false
        val valid = System.currentTimeMillis() - retiredAt <= RETIRED_RUN_TTL_MS
        if (!valid) {
            recentlyRetiredRuns.remove(runId, retiredAt)
        }
        return valid
    }

    fun recordFrameOwnership(conversationId: String, turn: IrohActiveTurn?): FrameOwnershipResult {
        val previous = frameOwnershipPath[conversationId]
        val current = if (turn != null) "engine" else "observer"
        val switched = previous != null && previous != current
        if (switched) {
            frameOwnershipPath[conversationId] = current
            return FrameOwnershipResult.Switched(from = previous, to = current)
        }
        if (previous == null) {
            frameOwnershipPath[conversationId] = current
        }
        return FrameOwnershipResult.Unchanged(current)
    }

    sealed interface FrameOwnershipResult {
        data class Unchanged(val current: String) : FrameOwnershipResult
        data class Switched(val from: String, val to: String) : FrameOwnershipResult
    }

    fun frameOwnership(conversationId: String): String? = frameOwnershipPath[conversationId]
    fun clearFrameOwnership(conversationId: String) {
        frameOwnershipPath.remove(conversationId)
    }

    fun rememberInterruptedTurns() {
        activeTurns.values.forEach { turn ->
            if (!turn.hasTerminal) {
                interruptedTurns[turn.conversationId] = RedialWhileTurnActive(
                    agentId = turn.agentId,
                    conversationId = turn.conversationId,
                    turnId = turn.turnId,
                    runId = turn.runId,
                )
            }
        }
    }

    fun clearInterruptedTurns() {
        interruptedTurns.clear()
    }

    fun getInterruptedTurn(conversationId: String): RedialWhileTurnActive? = interruptedTurns[conversationId]
    fun removeInterruptedTurn(conversationId: String): RedialWhileTurnActive? = interruptedTurns.remove(conversationId)
    fun removeInterruptedTurn(conversationId: String, recovery: RedialWhileTurnActive): Boolean =
        interruptedTurns.remove(conversationId, recovery)
    fun interruptedTurnsSnapshot(): List<RedialWhileTurnActive> = interruptedTurns.values.toList()
    fun activeTurnsSnapshot(): List<IrohActiveTurn> = activeTurns.values.toList()

    fun clear() {
        interruptedTurns.clear()
        recentlyRetiredRuns.clear()
        frameOwnershipPath.clear()
        activeSendJobs.values.forEach { it.cancel() }
        activeSendJobs.clear()
        activeTurns.values.forEach { turn ->
            turn.terminalReached.complete("disconnected")
        }
        activeTurns.clear()
    }

    fun allSendJobEntries(): List<Pair<String, Job>> =
        activeSendJobs.entries.map { it.key to it.value }

    fun activeTurnsCount(): Int = activeTurns.size
    fun activeSendJobsCount(): Int = activeSendJobs.size

    fun snapshotForTest(conversationId: String): IrohTurnSnapshot? {
        val turn = activeTurns[conversationId] ?: return null
        return IrohTurnSnapshot(
            token = turn.token,
            turnId = turn.turnId,
            runId = turn.runId,
            hasTerminal = turn.hasTerminal,
            isTerminalCompleted = turn.terminalReached.isCompleted,
        )
    }

    data class IrohTurnSnapshot(
        val token: IrohTurnToken,
        val turnId: String,
        val runId: String,
        val hasTerminal: Boolean,
        val isTerminalCompleted: Boolean,
    )

    companion object {
        const val RETIRED_RUN_TTL_MS = 5 * 60_000L
    }
}
