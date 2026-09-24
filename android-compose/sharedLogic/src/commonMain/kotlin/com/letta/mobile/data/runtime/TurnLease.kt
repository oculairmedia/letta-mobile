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
