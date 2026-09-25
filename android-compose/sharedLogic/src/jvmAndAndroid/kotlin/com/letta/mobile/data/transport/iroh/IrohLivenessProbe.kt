package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

import kotlin.time.Duration.Companion.milliseconds

/**
 * letta-mobile-wxy4s: APPLICATION-LEVEL CONNECTION LIVENESS.
 *
 * ROOT CAUSE it exists for (production incident 2026-07-31): the peer can vanish
 * without ever sending a CONNECTION_CLOSE (host reboot / NAT rebind / black-hole).
 * Neither of [IrohAppServerTransport]'s loss reporters — the `connection.closed()`
 * watcher and reader-loop exit — can fire then. Worse, its 15s UNACKED keepalive
 * datagram makes it actively harmful: `sendDatagram` succeeds locally regardless of
 * peer liveness and keeps resetting the local QUIC idle timer (RFC 9000 §10.1
 * resets on send OR receive), so even the idle timeout that would eventually have
 * killed the connection never fires. The keepalive MASKS the death. Both clients
 * therefore sat on dead connections for ~40 minutes rendering cached data.
 *
 * MECHANISM: issue `health.check` through the connection HANDLE's `adminRpc`, which
 * lands on `IrohAppServerTransport.adminRpcOverStream` whose first act is
 * `connection.openBi()`. Opening a fresh QUIC bidi stream is precisely the
 * operation that fails on a black-holed path and that an unacked datagram can never
 * test. It deliberately does NOT go through `IrohChannelTransport.adminRpc`, which
 * would run the retry/escalate ladder and pollute its viewed-conversation
 * bookkeeping.
 *
 * It is a LIVENESS test, not a health test: any answer from the peer counts as
 * alive (see [classifyProbeError]).
 *
 * letta-mobile-parg0: CONGESTION ≠ DEATH. Under heavy concurrent admin_rpc
 * (model.list / message.list hydrate), a fresh health.check can miss its probe
 * budget while the path is still alive. Recent proof-of-life (stream frames OR
 * successful admin_rpc) skips the probe; a timed-out probe while other young
 * admin_rpcs are in flight is recorded as congested and does not escalate.
 * Congested outcomes do not reset the failure streak; an absolute
 * [MAX_DETECTION_MS] deadline bounds deferral so detection stays ≤120s.
 *
 * letta-mobile-qygvv.22: IN-FLIGHT WORK IS NOT PROOF OF LIFE. A black-holed path also
 * has "young in-flight" admin_rpcs — they are the requests that are hanging. A probe
 * timeout is only soft-failed when the path has actually answered something inside
 * the probe window, or the in-flight work is too young to judge; see
 * [classifyProbeTimeout]. A timed-out probe is confirmed by an IMMEDIATE second probe
 * rather than one interval later, so a dead path is declared within
 * `failuresToDeclareDead × timeoutMs` of the first probe that saw it.
 *
 * @param millisSinceLastProofOfLife elapsed time since the last stream frame or
 *   successful admin_rpc; live traffic is already proof of life, so a probe due
 *   within that window is skipped.
 * @param adminRpcPathEvidence snapshot of the admin_rpc lane (young/oldest in-flight
 *   work, back-to-back request timeouts, proof-of-life age) for a given evidence
 *   window; decides whether a probe timeout is congestion or a dead path.
 * @param runtime scope + clock the loop runs on (wall clock in production, a virtual
 *   test scheduler in unit tests).
 * @param reportConnectionLost the supervisor's loss entry point. Attribution is
 *   MANDATORY: an unattributed report landing after a redial destroys the healthy
 *   NEW handle (the r3i1z regression), so the dying handle is always passed along.
 */
internal class IrohLivenessProbe(
    private val intervalMs: Long,
    private val timeoutMs: Long,
    private val failuresToDeclareDead: Int,
    private val maxDetectionMs: Long = MAX_DETECTION_MS,
    private val millisSinceLastProofOfLife: () -> Long,
    private val adminRpcPathEvidence: (windowMs: Long) -> AdminRpcPathEvidence =
        { AdminRpcPathEvidence.idle(millisSinceLastProofOfLife()) },
    private val runtime: Runtime = Runtime(),
    private val reportConnectionLost: (reason: String, handle: IrohConnectionHandle) -> Unit,
) {
    /**
     * Where and on what clock the loop runs. Production uses its own wall-clock IO
     * scope (see [scope]); unit tests pass a virtual-time scope + scheduler clock and
     * drive the loop with `advanceTimeBy` (never `advanceUntilIdle` — the loop is endless).
     */
    internal class Runtime(
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        val clock: () -> Long = System::currentTimeMillis,
    )

    /** Evidence window: everything since the previous tick plus this probe's wait. */
    private val evidenceWindowMs: Long get() = intervalMs + timeoutMs

    /**
     * The probe runs on its OWN wall-clock scope, never a caller's (the [Runtime]
     * default; only isolated unit tests substitute a virtual-time scope).
     *
     * Two reasons, both load-bearing:
     *  1. Liveness must be measured in real elapsed time. Parented to a caller's
     *     scope, a virtual test clock (`runTest` + `advanceUntilIdle`) fast-forwards
     *     the interval indefinitely and spins probe -> declare-dead -> redial at CPU
     *     speed — which is exactly what it did before this was split out.
     *  2. It is an endless supervision loop, so it must never count as outstanding
     *     structured-concurrency work for whoever owns the transport's scope.
     * Its lifetime is still explicit: [stop] cancels the job on any non-Ready
     * transition and on disconnect.
     */
    private val scope = runtime.scope

    /** Exactly one loop is ever live, and it is pinned to a connection generation. */
    private val generation = atomic(0)

    @Volatile
    private var job: Job? = null

    /**
     * Forced-probe wakeup. Conflated: many resume events collapse into one immediate
     * probe rather than queueing a burst.
     */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** Test/wiring visibility: is a probe loop currently armed? */
    val isArmed: Boolean get() = job?.isActive == true

    /**
     * Force one immediate probe and restart the interval. The periodic loop is not
     * reliable while an Android app is backgrounded (doze), so a returning user gets
     * sub-second detection instead of waiting a full interval. Safe to call when no
     * probe is armed — the signal is simply dropped.
     */
    fun probeNow() {
        wakeups.trySend(Unit)
        Telemetry.event("IrohLiveness", "probe.forced")
    }

    /** Arms the probe for [handle], superseding any previous generation. */
    fun start(handle: IrohConnectionHandle) {
        if (intervalMs <= 0L || timeoutMs <= 0L) {
            stop("probe_disabled")
            Telemetry.event("IrohLiveness", "probe.disabled", "intervalMs" to intervalMs.toString())
            return
        }
        val armed = generation.incrementAndGet()
        job?.cancel()
        Telemetry.event(
            "IrohLiveness", "probe.start",
            "sessionId" to handle.sessionId,
            "generation" to armed.toString(),
            "intervalMs" to intervalMs.toString(),
            "timeoutMs" to timeoutMs.toString(),
            "failuresToDeclareDead" to failuresToDeclareDead.toString(),
        )
        job = scope.launch { runLoop(handle, armed) }
    }

    fun stop(reason: String) {
        val running = job ?: return
        job = null
        // Invalidate the generation so an in-flight iteration drops its result.
        generation.incrementAndGet()
        running.cancel()
        Telemetry.event("IrohLiveness", "probe.stop", "reason" to reason)
    }

    /**
     * One probe cycle per interval until this loop's [armed] generation is
     * superseded or the connection is declared dead (after which the redial's fresh
     * Ready arms a new loop).
     */
    private suspend fun runLoop(handle: IrohConnectionHandle, armed: Int) {
        var consecutiveFailures = 0
        // Wall-clock bound across CONGESTED soft-fails: resetting the streak on
        // every congested probe can push declare-dead past MAX_DETECTION_MS
        // (RPC starts mid-interval → grace covers two probes → then N failures).
        var unhealthySinceMs: Long? = null
        var confirmImmediately = false
        while (generation.value == armed) {
            val outcome = awaitNextOutcome(handle, armed, confirmImmediately)
            // qygvv.22: a timeout already cost timeoutMs; confirm it right away.
            confirmImmediately = outcome == ProbeOutcome.TIMED_OUT
            val nowMs = runtime.clock()
            when (outcome) {
                ProbeOutcome.ALIVE -> {
                    consecutiveFailures = 0
                    unhealthySinceMs = null
                }
                ProbeOutcome.CONGESTED -> {
                    // Congestion is not proof of life — do not reset the streak —
                    // but also do not increment. Cap deferral with an absolute deadline.
                    val since = unhealthySinceMs ?: nowMs.also { unhealthySinceMs = it }
                    if (nowMs - since >= maxDetectionMs) {
                        declareDead(handle, consecutiveFailures.coerceAtLeast(1))
                        return
                    }
                }
                ProbeOutcome.TIMED_OUT, ProbeOutcome.UNREACHABLE -> {
                    consecutiveFailures += 1
                    val since = unhealthySinceMs ?: nowMs.also { unhealthySinceMs = it }
                    recordFailure(handle, outcome, consecutiveFailures)
                    if (consecutiveFailures >= failuresToDeclareDead ||
                        nowMs - since >= maxDetectionMs
                    ) {
                        declareDead(handle, consecutiveFailures)
                        return
                    }
                }
            }
        }
    }

    /**
     * One tick of the loop: wait the interval, then either skip (recent traffic
     * already proves life) or run a bounded probe. A superseded generation
     * reports ALIVE so the caller's loop condition ends it without side effects.
     * [immediate] skips the interval wait (confirmation of a timed-out probe) but still
     * honours fresh proof of life.
     */
    private suspend fun awaitNextOutcome(handle: IrohConnectionHandle, armed: Int, immediate: Boolean): ProbeOutcome {
        val forced = if (immediate) false else awaitTick()
        if (generation.value != armed) return ProbeOutcome.ALIVE
        if (skipForRecentProofOfLife(forced)) return ProbeOutcome.ALIVE
        val outcome = probeOnce(handle)
        return if (generation.value == armed) outcome else ProbeOutcome.ALIVE
    }

    private fun recordFailure(handle: IrohConnectionHandle, outcome: ProbeOutcome, consecutiveFailures: Int) {
        Telemetry.event(
            "IrohLiveness", "probe.failed",
            "sessionId" to handle.sessionId,
            "consecutiveFailures" to consecutiveFailures.toString(),
            "timedOut" to (outcome == ProbeOutcome.TIMED_OUT),
            "proofOfLifeAgeMs" to millisSinceLastProofOfLife().toString(),
            "youngInFlightAdminRpc" to adminRpcPathEvidence(evidenceWindowMs).youngInFlight.toString(),
        )
    }

    /** Waits one interval; returns true when woken early by [probeNow]. */
    private suspend fun awaitTick(): Boolean =
        withTimeoutOrNull(intervalMs.milliseconds) { wakeups.receive() } != null

    /**
     * Live stream frames OR successful admin_rpc are already proof of life — never
     * probe (or escalate) while that window is fresh. A forced probe (app resume)
     * bypasses this: "recent" activity may predate a long background window.
     */
    private fun skipForRecentProofOfLife(forced: Boolean): Boolean {
        if (forced) return false
        val ageMs = millisSinceLastProofOfLife()
        val skip = ageMs < intervalMs
        if (skip) {
            Telemetry.event(
                "IrohLiveness", "probe.skipped",
                "reason" to "recent_proof_of_life",
                "ageMs" to ageMs.toString(),
            )
        }
        return skip
    }

    /**
     * One bounded `health.check` round trip.
     *
     * TIMEOUT TRAP: `health.check` is legacy-fallback-safe, so a stream failure
     * falls back to the control channel with its own 30s timeout (worst case ~60s
     * per call). The probe MUST bound the call itself rather than inherit that.
     *
     * letta-mobile-parg0 / qygvv.22: a timeout while other admin_rpcs are in flight is
     * congestion only when the path has otherwise proved alive — see
     * [classifyProbeTimeout] — so a live-but-busy path is not torn down while a
     * black-holed one still is.
     */
    private suspend fun probeOnce(handle: IrohConnectionHandle): ProbeOutcome {
        val outcome = withTimeoutOrNull(timeoutMs.milliseconds) {
            try {
                // ANY response — including success=false — proves the peer answered,
                // which is the only thing this probe measures. Escalating on an
                // unhealthy-but-reachable server would redial pointlessly.
                handle.adminRpc(method = HEALTH_CHECK_METHOD, path = HEALTH_CHECK_PATH, body = null)
                ProbeOutcome.ALIVE
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                classifyProbeError(error)
            }
        } ?: ProbeOutcome.TIMED_OUT

        return if (outcome == ProbeOutcome.TIMED_OUT) classifyTimeout(handle) else outcome
    }

    private fun classifyTimeout(handle: IrohConnectionHandle): ProbeOutcome {
        val evidence = adminRpcPathEvidence(evidenceWindowMs)
        val verdict = classifyProbeTimeout(evidence, timeoutMs, evidenceWindowMs)
        if (verdict == ProbeTimeoutVerdict.NO_IN_FLIGHT) return ProbeOutcome.TIMED_OUT
        Telemetry.event(
            "IrohLiveness", if (verdict.congested) "probe.congested" else "probe.escalated",
            "sessionId" to handle.sessionId,
            "verdict" to verdict.name,
            "youngInFlightAdminRpc" to evidence.youngInFlight.toString(),
            "oldestInFlightAgeMs" to evidence.oldestInFlightAgeMs?.toString(),
            "recentRequestTimeouts" to evidence.recentRequestTimeouts.toString(),
            "proofOfLifeAgeMs" to evidence.proofOfLifeAgeMs.toString(),
            "timeoutMs" to timeoutMs.toString(),
        )
        return if (verdict.congested) ProbeOutcome.CONGESTED else ProbeOutcome.TIMED_OUT
    }

    /**
     * Same rule as `IrohChannelTransport.adminRpc`'s request-isolation guard: a
     * decode or frame-size rejection is a PAYLOAD fault, which means the round trip
     * COMPLETED — the path is alive. Likewise any error that doesn't look
     * connection-lost (e.g. a method the node doesn't implement) came back FROM the
     * peer. Only a connection-class error counts against liveness.
     */
    private fun classifyProbeError(error: Throwable): ProbeOutcome {
        val alive = error.isAdminRpcPayloadError() || !error.isConnectionLostClass()
        Telemetry.event(
            "IrohLiveness", "probe.error",
            "error" to (error.message ?: error.toString()),
            "class" to error::class.simpleName,
            "aliveDespiteError" to alive,
        )
        return if (alive) ProbeOutcome.ALIVE else ProbeOutcome.UNREACHABLE
    }

    private fun declareDead(handle: IrohConnectionHandle, failures: Int) {
        Telemetry.event(
            "IrohLiveness", "probe.declared_dead",
            "sessionId" to handle.sessionId,
            "failures" to failures.toString(),
            "proofOfLifeAgeMs" to millisSinceLastProofOfLife().toString(),
            "youngInFlightAdminRpc" to adminRpcPathEvidence(evidenceWindowMs).youngInFlight.toString(),
        )
        reportConnectionLost(
            "liveness_probe_failed: no health.check response in ${timeoutMs}ms x $failures",
            handle,
        )
    }

    private enum class ProbeOutcome { ALIVE, TIMED_OUT, UNREACHABLE, CONGESTED }

    companion object {
        /**
         * Interval is deliberately LONGER than IrohAppServerTransport's 15s keepalive
         * (the probe is the real liveness signal; the keepalive only masks death).
         * One tiny bidi stream per interval => few RPCs/min steady state.
         *
         * letta-mobile-parg0: timeout 10s / 2 failures. Congestion soft-fails must not
         * erase the failure streak or unbounded-defer detection: worst case including
         * [CONGESTION_GRACE_MS] deferral stays ≤ [MAX_DETECTION_MS] (wiring gate).
         * Budget: GRACE + FAILURES*(INTERVAL+TIMEOUT) = 45s + 2*30s = 105s ≤ 120s.
         */
        const val INTERVAL_MS = 20_000L
        const val TIMEOUT_MS = 10_000L
        const val FAILURES_TO_DECLARE_DEAD = 2

        /**
         * Absolute wall-clock cap from the first non-ALIVE probe outcome (including
         * CONGESTED) until declare-dead. Prevents congestion soft-fails from pushing
         * detection past the 120s requirement.
         */
        const val MAX_DETECTION_MS = 120_000L

        /**
         * In-flight admin_rpc younger than this still count as congestion evidence.
         * Matches [IrohAppServerTransport.ADMIN_RPC_TIMEOUT_MS] (30s) plus slack so a
         * nearly-timed-out hydrate still protects the probe.
         */
        const val CONGESTION_GRACE_MS = 45_000L

        const val HEALTH_CHECK_METHOD = "health.check"
        const val HEALTH_CHECK_PATH = "/v1/health"
    }
}

/**
 * k7yyc: true when [this] (or any cause in its chain) is a frame codec decode/size
 * rejection — a per-request payload fault that must fail only the request, never
 * trigger a transport reconnect.
 */
internal fun Throwable.isAdminRpcPayloadError(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is IrohFrameCodec.ProtocolException) return true
        current = current.cause
    }
    return false
}

/** Heuristic: does this error text look like the connection itself went away? */
internal fun Throwable.isConnectionLostClass(): Boolean {
    val text = listOfNotNull(message, this::class.simpleName).joinToString(" ").lowercase()
    return listOf("closed", "timeout", "timed out", "reset", "broken pipe", "connection", "stream")
        .any { it in text }
}
