package com.letta.mobile.data.runtime
import kotlinx.coroutines.Job

/**
 * Owner-token turn lease (letta-mobile-lgns8.22.2).
 *
 * Replaces tokenless [Mutex] force-unlock. Only the structured scope that
 * holds [token] may release the lease, or a coordinator that first cancels and
 * joins [ownerJob] after marking [phase] [TurnLeasePhase.Retiring].
 */
data class TurnLease(
    val token: Long,
    val runtimeId: String,
    val agentId: String?,
    val conversationId: String?,
    val acquiredAtMs: Long,
    val phase: TurnLeasePhase,
    val ownerJob: Job? = null,
    val runId: String? = null,
    val connectionGeneration: Long = 0L,
    val processRole: String? = null,
    val lastTerminal: String? = null,
    val lastTerminalSource: String? = null,
    val lastTerminalAtMs: Long? = null,
    val lastTerminalSeq: Long? = null,
    val lastTerminalScopeMatched: Boolean? = null,
    val settleDeadlineMs: Long? = null,
    val watchdogDeadlineMs: Long? = null,
    val releaseReason: String? = null,
) {
    /**
     * Preparing/Starting are locally alive even when the provider has no run yet.
     * Queued is too: the server acknowledged the input into its queue, so an idle
     * run list is expected and is not evidence the owner died.
     */
    val isLocallyAliveWithoutRun: Boolean
        get() = phase == TurnLeasePhase.Preparing ||
            phase == TurnLeasePhase.Starting ||
            phase == TurnLeasePhase.Queued

    /** Retiring or Terminal: the lease is already on its way out. */
    val isEnding: Boolean
        get() = phase == TurnLeasePhase.Retiring || phase == TurnLeasePhase.Terminal
}

enum class TurnLeasePhase {
    Preparing,
    Starting,
    /**
     * letta-mobile-qygvv.1: `input_accepted{disposition: queued}` — the input waits
     * behind an active turn or pending approval. The idle watchdog is paused until
     * the first stream frame (or `update_queue` dequeue) shows the turn started.
     */
    Queued,
    Streaming,
    Retiring,
    Terminal,
}

/**
 * A slot plus the token and queued input tracker of the lease this turn owns inside it.
 */
internal class LeaseRef(
    val slot: TurnLeaseSlot,
    val token: Long,
    val queuedInput: QueuedInputTracker,
) {
    val key: TurnRuntimeKey get() = slot.key
    /** The slot still holds OUR lease (not a successor's). */
    val current: TurnLease? get() = slot.lease?.takeIf { it.token == token }
}

internal fun TurnLease.toInitialOwner(): AppServerTurnEngine.ActiveTurnOwner = AppServerTurnEngine.ActiveTurnOwner(
    runId = null,
    runtimeId = runtimeId,
    agentId = agentId,
    conversationId = conversationId,
    acquiredAtMs = acquiredAtMs,
    lastTerminal = null,
    processRole = processRole,
    settleDeadlineMs = settleDeadlineMs,
    watchdogDeadlineMs = watchdogDeadlineMs,
)

/** Whether this owner telemetry was recorded for [lease] (same runtime, conversation and acquire time). */
internal fun AppServerTurnEngine.ActiveTurnOwner.isFor(lease: TurnLease): Boolean =
    runtimeId == lease.runtimeId &&
        conversationId == lease.conversationId &&
        acquiredAtMs == lease.acquiredAtMs
