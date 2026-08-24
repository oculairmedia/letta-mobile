package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.api.RedialWhileTurnActive
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/** Per-turn state shared between streaming send jobs, observer ingest, and cancellation. */
class IrohActiveTurn(request: IrohTurnRequest) {
    val token = request.token
    private val runIdRef = atomic(request.runId.value)
    private val terminalClaimed = atomic<IrohTerminalSource?>(null)

    /** Completes when a single terminal is emitted or the turn is retired. */
    val terminalReached = CompletableDeferred<IrohTerminalStatus>()

    @Volatile
    var job: Job? = null

    private val agent = request.agentId
    val conversationId: String get() = token.conversationId.value
    val turnId: String get() = token.turnId.value
    val agentId: String get() = agent.value
    val runId: String get() = runIdRef.value
    val hasTerminal: Boolean get() = terminalClaimed.value != null
    val terminalSource: IrohTerminalSource? get() = terminalClaimed.value

    fun promoteRunId(promotion: IrohRunPromotion): Boolean {
        if (promotion.token != token) return false
        val candidate = promotion.runId.value
        if (candidate.isBlank()) return false
        if (candidate.isIrohSyntheticRunId()) return false
        while (true) {
            val current = runIdRef.value
            if (!current.isIrohSyntheticRunId() || current == candidate) return false
            if (runIdRef.compareAndSet(current, candidate)) return true
        }
    }

    fun tryClaimTerminal(source: IrohTerminalSource): Boolean =
        terminalClaimed.compareAndSet(expect = null, update = source)
}

sealed interface IrohTryStartResult {
    data class Started(val turn: IrohActiveTurn) : IrohTryStartResult
    data class Busy(val activeTurn: IrohActiveTurn, val rejectedToken: IrohTurnToken) : IrohTryStartResult
}

/**
 * Atomic ownership registry for active Iroh turns and their auxiliary lifecycle state.
 * All operations identify a turn by its immutable value objects, preventing unrelated
 * conversations from affecting each other's send jobs, terminals, or frame ownership.
 */
class IrohTurnRegistry {
    private val activeTurns = ConcurrentHashMap<String, IrohActiveTurn>()
    private val activeSendJobs = ConcurrentHashMap<String, Job>()
    private val frameOwnership = ConcurrentHashMap<String, IrohFrameOwner>()
    private val recentlyRetiredRuns = ConcurrentHashMap<String, Long>()
    private val interruptedTurns = ConcurrentHashMap<String, RedialWhileTurnActive>()

    fun tryStart(request: IrohTurnRequest): IrohTryStartResult {
        val newTurn = IrohActiveTurn(request)
        var busy: IrohActiveTurn? = null
        activeTurns.compute(request.token.conversationId.value) { _, existing ->
            if (existing != null && !existing.terminalReached.isCompleted) {
                busy = existing
                existing
            } else {
                newTurn
            }
        }
        return busy?.let { IrohTryStartResult.Busy(it, request.token) }
            ?: IrohTryStartResult.Started(newTurn)
    }

    fun registerSendJob(registration: IrohSendJobRegistration) {
        activeSendJobs[registration.conversationId.value] = registration.job
    }

    fun unregisterSendJob(registration: IrohSendJobRegistration): Boolean =
        activeSendJobs.remove(registration.conversationId.value, registration.job)

    fun removeSendJob(conversationId: IrohConversationId): Job? = activeSendJobs.remove(conversationId.value)
    fun getSendJob(conversationId: IrohConversationId): Job? = activeSendJobs[conversationId.value]
    fun getActiveTurn(conversationId: IrohConversationId): IrohActiveTurn? = activeTurns[conversationId.value]

    fun hasActiveTurn(conversationId: IrohConversationId): Boolean =
        activeTurns[conversationId.value]?.terminalReached?.isCompleted == false

    val hasAnyActiveTurn: Boolean
        get() = activeTurns.values.any { !it.terminalReached.isCompleted }

    fun concurrentTurns(excludingConversationId: IrohConversationId): List<IrohActiveTurn> =
        activeTurns.values.filter { it.token.conversationId != excludingConversationId && !it.hasTerminal }

    fun promoteRunId(promotion: IrohRunPromotion): Boolean =
        getActiveTurn(promotion.token.conversationId)?.promoteRunId(promotion) == true

    /**
     * Reserves the exactly-once terminal slot without making the turn inactive.
     * The owner must emit its frame before calling [retireClaimed], so observers
     * cannot see a drained registry before the winning terminal is observable.
     */
    fun claimTerminal(publication: IrohTerminalPublication): Boolean =
        publication.turn.tryClaimTerminal(publication.source)

    fun finish(token: IrohTurnToken): Boolean {
        val turn = getActiveTurn(token.conversationId) ?: return false
        if (turn.token != token) return false
        val removed = activeTurns.remove(token.conversationId.value, turn)
        if (removed) frameOwnership.remove(token.conversationId.value)
        return removed
    }

    fun isRetiredRun(runId: IrohRunId): Boolean {
        val retiredAt = recentlyRetiredRuns[runId.value] ?: return false
        val valid = System.currentTimeMillis() - retiredAt <= RETIRED_RUN_TTL_MS
        if (!valid) recentlyRetiredRuns.remove(runId.value, retiredAt)
        return valid
    }

    fun recordFrameOwnership(conversationId: IrohConversationId, turn: IrohActiveTurn?): FrameOwnershipResult {
        val owner = if (turn == null) IrohFrameOwner.Observer else IrohFrameOwner.Engine
        val previous = frameOwnership.put(conversationId.value, owner)
        return if (previous != null && previous != owner) FrameOwnershipResult.Switched(previous, owner)
        else FrameOwnershipResult.Unchanged(owner)
    }

    fun clearFrameOwnership(conversationId: IrohConversationId) {
        frameOwnership.remove(conversationId.value)
    }

    fun rememberInterruptedTurns() {
        activeTurns.values.filterNot(IrohActiveTurn::hasTerminal).forEach { turn ->
            interruptedTurns[turn.token.conversationId.value] = RedialWhileTurnActive(
                agentId = turn.agentId,
                conversationId = turn.token.conversationId.value,
                turnId = turn.token.turnId.value,
                runId = turn.runId,
            )
        }
    }

    fun clearInterruptedTurns() = interruptedTurns.clear()
    fun getInterruptedTurn(conversationId: IrohConversationId): RedialWhileTurnActive? = interruptedTurns[conversationId.value]
    fun removeInterruptedTurn(conversationId: IrohConversationId): RedialWhileTurnActive? = interruptedTurns.remove(conversationId.value)
    fun removeInterruptedTurn(conversationId: IrohConversationId, recovery: RedialWhileTurnActive): Boolean =
        interruptedTurns.remove(conversationId.value, recovery)
    fun interruptedTurnsSnapshot(): List<RedialWhileTurnActive> = interruptedTurns.values.toList()
    fun activeTurnsSnapshot(): List<IrohActiveTurn> = activeTurns.values.toList()

    /**
     * Claims every currently active turn for disconnect before its job is cancelled.
     * This prevents a cancelled job's completion handler from removing the turn before
     * disconnect has emitted its deterministic terminal.
     */
    fun claimDisconnectTerminals(): List<IrohActiveTurn> =
        activeTurns.values.filter { it.tryClaimTerminal(IrohTerminalSource.Disconnect) }

    fun cancelSendJobs() {
        activeSendJobs.values.forEach(Job::cancel)
        activeSendJobs.clear()
    }

    /** Retires a terminal that was claimed by [claimDisconnectTerminals]. */
    fun retireClaimed(publication: IrohTerminalPublication): Boolean {
        if (publication.turn.terminalSource != publication.source) return false
        retire(publication)
        return true
    }

    fun clear() {
        interruptedTurns.clear()
        recentlyRetiredRuns.clear()
        frameOwnership.clear()
        cancelSendJobs()
        activeTurns.values.forEach { it.terminalReached.complete(IrohTerminalStatus("disconnected")) }
        activeTurns.clear()
    }

    fun allSendJobEntries(): List<IrohSendJobRegistration> = activeSendJobs.entries.map {
        IrohSendJobRegistration(IrohConversationId(it.key), it.value)
    }

    fun activeTurnsCount(): Int = activeTurns.size
    fun activeSendJobsCount(): Int = activeSendJobs.size

    fun snapshotForTest(conversationId: IrohConversationId): IrohTurnSnapshot? =
        getActiveTurn(conversationId)?.let(IrohTurnSnapshot::from)

    private fun retire(publication: IrohTerminalPublication) {
        val turn = publication.turn
        val conversationId = turn.token.conversationId
        if (interruptedTurns[conversationId.value]?.turnId == turn.token.turnId.value) {
            interruptedTurns.remove(conversationId.value)
        }
        frameOwnership.remove(conversationId.value)
        rememberRetiredRun(IrohRunId(turn.runId))
        activeTurns.remove(conversationId.value, turn)
        turn.terminalReached.complete(publication.status)
    }

    private fun rememberRetiredRun(runId: IrohRunId) {
        if (runId.value.isBlank()) return
        val now = System.currentTimeMillis()
        recentlyRetiredRuns.entries.removeIf { now - it.value > RETIRED_RUN_TTL_MS }
        recentlyRetiredRuns[runId.value] = now
    }

    sealed interface FrameOwnershipResult {
        data class Unchanged(val current: IrohFrameOwner) : FrameOwnershipResult
        data class Switched(val from: IrohFrameOwner, val to: IrohFrameOwner) : FrameOwnershipResult
    }

    data class IrohTurnSnapshot(
        val token: IrohTurnToken,
        val turnId: IrohTurnId,
        val runId: IrohRunId,
        val hasTerminal: Boolean,
        val isTerminalCompleted: Boolean,
    ) {
        companion object {
            fun from(turn: IrohActiveTurn) = IrohTurnSnapshot(
                token = turn.token,
                turnId = turn.token.turnId,
                runId = IrohRunId(turn.runId),
                hasTerminal = turn.hasTerminal,
                isTerminalCompleted = turn.terminalReached.isCompleted,
            )
        }
    }

    companion object {
        const val RETIRED_RUN_TTL_MS = 5 * 60_000L
    }
}
