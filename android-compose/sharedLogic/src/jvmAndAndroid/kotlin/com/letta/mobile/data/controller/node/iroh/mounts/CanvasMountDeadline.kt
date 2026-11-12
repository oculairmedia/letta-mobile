package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.util.runCatchingCancellable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounds one mount call (finding F5: the interprocess stripe lock waits without limit, so a hung holder must
 * not pin a request forever).
 *
 * The work runs in the host-owned [scope] (cancelled when the host stops), so a call stuck in non-cancellable
 * I/O cannot hold the request either.
 * Past [limit] the work is cancelled and given [grace] to unwind: before a mutation's commit point cancellation
 * propagates out of the store, after it the store still returns its outcome (see the mount store), so the caller
 * can tell the two apart with [CanvasMountCallTrace]. A cancelled call never reaches the commit point later: the
 * store checks cancellation on entry and immediately before its atomic move.
 */
internal class CanvasMountDeadline(
    private val limit: Duration,
    private val grace: Duration,
    private val scope: CoroutineScope,
) {

    /** Runs [work]; [CanvasMountTimed.Expired] once [limit] passes. Cancelling the caller cancels the work. */
    suspend fun run(work: suspend () -> CanvasMountOutcome): CanvasMountTimed {
        val settled = CompletableDeferred<CanvasMountOutcome>()
        val job = scope.launch {
            runCatchingCancellable { work() }.fold(settled::complete, settled::completeExceptionally)
        }
        try {
            withTimeoutOrNull(limit) { settled.await() }?.let { return CanvasMountTimed.Settled(it) }
            job.cancel()
            withTimeoutOrNull(grace) { job.join() }
            // Unwound: the work finished without producing a value, which only cancellation does.
            return CanvasMountTimed.Expired(unwound = job.isCompleted && !settled.isCompleted)
        } finally {
            job.cancel()
        }
    }

    /** Production bounds: well inside the transport's per-request timeout. */
    companion object {
        /** The deadline every production mount call runs under. */
        fun production(scope: CoroutineScope): CanvasMountDeadline = CanvasMountDeadline(10.seconds, 2.seconds, scope)
    }
}

/** How a bounded call ended. */
internal sealed interface CanvasMountTimed {
    /** The work finished in time with [value]. */
    data class Settled(val value: CanvasMountOutcome) : CanvasMountTimed

    /** The deadline passed; [unwound] says the work then ended by cancellation without producing anything. */
    data class Expired(val unwound: Boolean) : CanvasMountTimed
}
