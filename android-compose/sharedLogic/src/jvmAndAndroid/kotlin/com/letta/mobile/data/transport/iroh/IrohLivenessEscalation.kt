package com.letta.mobile.data.transport.iroh

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * letta-mobile-qygvv.22: what the admin_rpc lane says about the path at the moment a
 * liveness probe timed out.
 *
 * @param youngInFlight admin_rpcs still open and younger than the congestion grace.
 * @param oldestInFlightAgeMs age of the oldest still-open admin_rpc, or null when none.
 * @param recentRequestTimeouts consecutive `admin_rpc.request_isolated` failures (no
 *   success in between) that landed inside the evidence window.
 * @param proofOfLifeAgeMs elapsed time since the last stream frame or completed admin_rpc.
 */
internal data class AdminRpcPathEvidence(
    val youngInFlight: Int,
    val oldestInFlightAgeMs: Long?,
    val recentRequestTimeouts: Int,
    val proofOfLifeAgeMs: Long,
) {
    companion object {
        /** No in-flight work and no recorded timeouts: a probe timeout stands on its own. */
        fun idle(proofOfLifeAgeMs: Long) = AdminRpcPathEvidence(
            youngInFlight = 0,
            oldestInFlightAgeMs = null,
            recentRequestTimeouts = 0,
            proofOfLifeAgeMs = proofOfLifeAgeMs,
        )
    }
}

/**
 * Why a timed-out probe was (or was not) held back as congestion. [congested] is the
 * only thing the probe loop acts on; the name goes to telemetry.
 */
internal enum class ProbeTimeoutVerdict(val congested: Boolean) {
    /** Nothing else in flight: the probe timeout is the only signal (pre-qygvv.22 rule). */
    NO_IN_FLIGHT(congested = false),

    /** Something completed inside the window — the path is alive, just slow (parg0). */
    RECENT_PROOF_OF_LIFE(congested = true),

    /** In-flight admin_rpcs are themselves timing out back to back: the path is dead. */
    CONSECUTIVE_RPC_TIMEOUTS(congested = false),

    /** The oldest in-flight admin_rpc has outlived the probe budget without an answer. */
    OLDEST_IN_FLIGHT_STALLED(congested = false),

    /** In-flight work is younger than the probe budget: not enough evidence either way. */
    YOUNG_IN_FLIGHT(congested = true),
}

/**
 * letta-mobile-qygvv.22: classifies a timed-out liveness probe.
 *
 * INCIDENT (2026-09-25): a Tailscale path flap black-holed the phone's Iroh connection
 * for ~40 s. Six admin_rpcs were in flight, so the parg0 rule ("young in-flight work ⇒
 * congestion") soft-failed every probe and no redial happened; each request waited out
 * the full 30 s admin_rpc timeout. In-flight work is only evidence of CONGESTION when
 * the path is still answering. Order matters:
 *  1. no in-flight work → plain timeout;
 *  2. any completion (stream frame or admin_rpc) within [windowMs] → alive, congested;
 *  3. ≥ [DEAD_PATH_RPC_TIMEOUTS] back-to-back admin_rpc timeouts in the window → dead;
 *  4. the oldest in-flight admin_rpc is older than the probe budget → dead: the probe
 *     and that request both waited [timeoutMs] on the same path and neither got a byte;
 *  5. otherwise the in-flight work is too young to judge → congested (unchanged parg0).
 */
internal fun classifyProbeTimeout(
    evidence: AdminRpcPathEvidence,
    timeoutMs: Long,
    windowMs: Long,
): ProbeTimeoutVerdict = when {
    evidence.youngInFlight <= 0 -> ProbeTimeoutVerdict.NO_IN_FLIGHT
    evidence.proofOfLifeAgeMs < windowMs -> ProbeTimeoutVerdict.RECENT_PROOF_OF_LIFE
    evidence.recentRequestTimeouts >= DEAD_PATH_RPC_TIMEOUTS -> ProbeTimeoutVerdict.CONSECUTIVE_RPC_TIMEOUTS
    (evidence.oldestInFlightAgeMs ?: 0L) > timeoutMs -> ProbeTimeoutVerdict.OLDEST_IN_FLIGHT_STALLED
    else -> ProbeTimeoutVerdict.YOUNG_IN_FLIGHT
}

/** Back-to-back admin_rpc timeouts inside one probe window that prove a dead path. */
internal const val DEAD_PATH_RPC_TIMEOUTS = 2

/**
 * letta-mobile-qygvv.22: back-to-back `admin_rpc.request_isolated` failures on one
 * connection generation. Any proof of life resets it, so only an unbroken run of
 * failures — no answer of any kind in between — counts as dead-path evidence.
 */
internal class AdminRpcTimeoutStreak(private val clock: () -> Long = System::currentTimeMillis) {
    private val failuresAtMs = ConcurrentLinkedDeque<Long>()

    fun record() {
        failuresAtMs.addLast(clock())
        while (failuresAtMs.size > MAX_TRACKED) failuresAtMs.pollFirst()
    }

    fun reset() = failuresAtMs.clear()

    fun countWithin(windowMs: Long): Int {
        val now = clock()
        return failuresAtMs.count { now - it in 0..windowMs }
    }

    private companion object {
        const val MAX_TRACKED = 16
    }
}
