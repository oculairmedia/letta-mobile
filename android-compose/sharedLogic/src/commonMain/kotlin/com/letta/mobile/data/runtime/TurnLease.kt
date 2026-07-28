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
    /** Preparing/Starting are locally alive even when the provider has no run yet. */
    val isLocallyAliveWithoutRun: Boolean
        get() = phase == TurnLeasePhase.Preparing || phase == TurnLeasePhase.Starting
}

enum class TurnLeasePhase {
    Preparing,
    Starting,
    Streaming,
    Retiring,
    Terminal,
}
