package com.letta.mobile.data.transport.iroh

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/**
 * Per-turn state shared by the streaming send job and cancellation path.
 * Atomic claims keep observer, engine, and synthetic terminals exactly-once.
 */
internal class IrohActiveTurn(
    val turnId: String,
    initialRunId: String,
    val agentId: String,
    val conversationId: String,
) {
    private val runIdRef = atomic(initialRunId)
    private val terminalClaimed = atomic<String?>(null)

    /** Completes with the winning terminal status after transport cleanup. */
    val terminalReached = CompletableDeferred<String>()

    @Volatile
    var job: Job? = null

    /** The canonical run id — the real server run id once promoted. */
    val runId: String get() = runIdRef.value
    val hasTerminal: Boolean get() = terminalClaimed.value != null

    /** Promotes the synthetic run id once the server supplies its real id. */
    fun promoteRunId(real: String): Boolean {
        if (real.isBlank() || real.isIrohSyntheticRunId()) return false
        while (true) {
            val current = runIdRef.value
            if (!current.isIrohSyntheticRunId() || current == real) return false
            if (runIdRef.compareAndSet(current, real)) return true
        }
    }

    /** Wins exactly once; the first server, observer, or cancel source claims it. */
    fun tryClaimTerminal(source: String): Boolean = terminalClaimed.compareAndSet(expect = null, update = source)

    fun claimTerminal(): Boolean = tryClaimTerminal("engine")
}
