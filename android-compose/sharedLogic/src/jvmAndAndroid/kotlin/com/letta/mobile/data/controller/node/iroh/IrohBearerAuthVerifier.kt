package com.letta.mobile.data.controller.node.iroh

import java.security.MessageDigest
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy

/**
 * Hardened bootstrap bearer verification for Iroh connections
 * constant-time token comparison, per-NodeId failure
 * rate limiting with a lockout window, and outcomes that never carry the
 * provided or expected secret.
 *
 * Rate-limit state is scoped to the verifier instance; production shares one
 * instance per endpoint (every accepted connection of an [IrohNodeEndpoint]),
 * so redialing cannot reset a peer's failure budget.
 *
 * At most [MAX_TRACKED_PEERS] NodeIds hold failure state, so a flood of fresh
 * NodeIds cannot grow memory. A full table never evicts a NodeId whose failures
 * are still inside the window (that would lift its lockout or refund its
 * budget): expired entries are dropped first, and when none are, a failure
 * from a NodeId that cannot be tracked is answered [Outcome.RateLimited] at
 * once. A valid credential is still accepted from an untracked NodeId.
 */
class IrohBearerAuthVerifier(
    private val policy: IrohAuthPolicy,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val maxFailuresPerWindow: Int = MAX_FAILURES_PER_WINDOW,
    private val lockoutWindowMs: Long = LOCKOUT_WINDOW_MS,
) {
    sealed interface Outcome {
        data object Authenticated : Outcome

        /** [reason] is a fixed diagnostic label; never derived from the secret. */
        data class Denied(val reason: String) : Outcome

        /** Too many recent failures from this NodeId; fail closed even for a valid token. */
        data object RateLimited : Outcome
    }

    private val failureTimestamps = LinkedHashMap<String, ArrayDeque<Long>>()
    private val lock = Any()

    fun verify(remoteEndpointId: String, providedToken: String?): Outcome {
        if (isRateLimited(remoteEndpointId)) return Outcome.RateLimited
        // letta-mobile-dpv7m.1: no bootstrap credential configured means NO
        // bearer path, not an open door. Only a policy that pre-authenticates
        // this exact peer (anonymous test mode, static allowlist) passes;
        // everyone else must be paired or redeem an invite.
        val expected = policy.requiredBearerToken
            ?: return if (policy.preAuthenticates(remoteEndpointId)) {
                Outcome.Authenticated
            } else {
                deny(remoteEndpointId, "pairing_required")
            }
        // letta-mobile-by4vm.35: a configured allowlist binds the static token
        // to the listed NodeIds even when pairing admits unlisted peers, so a
        // leaked token is useless from any other key. Paired peers and invite
        // redemption authenticate before reaching this path.
        if (isOutsideAllowlist(remoteEndpointId)) return deny(remoteEndpointId, "peer_not_allowlisted")
        return verifyToken(remoteEndpointId, expected, providedToken)
    }

    private fun isOutsideAllowlist(remoteEndpointId: String): Boolean =
        policy.allowedPeerIds.isNotEmpty() && remoteEndpointId !in policy.allowedPeerIds

    private fun verifyToken(remoteEndpointId: String, expected: String, providedToken: String?): Outcome {
        val matches = providedToken != null &&
            MessageDigest.isEqual(
                expected.encodeToByteArray(),
                providedToken.encodeToByteArray(),
            )
        return if (matches) {
            Outcome.Authenticated
        } else {
            deny(remoteEndpointId, if (providedToken.isNullOrBlank()) "missing_token" else "invalid_token")
        }
    }

    /**
     * Count a failed credential attempt that did not go through [verify] — an
     * invalid/expired invite redemption — against the same per-NodeId
     * budget, so invite guessing is rate limited exactly like bearer guessing.
     */
    fun deny(remoteEndpointId: String, reason: String): Outcome {
        if (!recordFailure(remoteEndpointId)) return Outcome.RateLimited
        return if (isRateLimited(remoteEndpointId)) Outcome.RateLimited else Outcome.Denied(reason)
    }

    fun isRateLimited(remoteEndpointId: String): Boolean = synchronized(lock) {
        val cutoff = nowMs() - lockoutWindowMs
        val failures = failureTimestamps[remoteEndpointId] ?: return false
        while (failures.isNotEmpty() && failures.first() < cutoff) failures.removeFirst()
        if (failures.isEmpty()) failureTimestamps.remove(remoteEndpointId)
        failures.size >= maxFailuresPerWindow
    }

    /** How many NodeIds currently hold failure state; for tests and diagnostics. */
    internal fun trackedPeerCount(): Int = synchronized(lock) { failureTimestamps.size }

    /**
     * Counts one failure; false when it could not be counted because every tracked NodeId is still inside the
     * window. A NodeId is re-inserted at the tail on each failure, so the table stays ordered by newest failure
     * and expired NodeIds lead it.
     */
    private fun recordFailure(remoteEndpointId: String): Boolean = synchronized(lock) {
        val failures = failureTimestamps.remove(remoteEndpointId)
            ?: ArrayDeque<Long>().takeIf { hasRoomLocked() }
            ?: return false
        failures.addLast(nowMs())
        // Bound memory per peer: older entries beyond the limit can never
        // matter once the window check has run.
        while (failures.size > maxFailuresPerWindow) failures.removeFirst()
        failureTimestamps[remoteEndpointId] = failures
        true
    }

    /**
     * Whether a new NodeId fits, after dropping the leading NodeIds whose newest failure has left the window:
     * they are no longer limited and hold no budget. A NodeId still inside the window is never dropped.
     */
    private fun hasRoomLocked(): Boolean {
        val cutoff = nowMs() - lockoutWindowMs
        val oldestFirst = failureTimestamps.values.iterator()
        while (failureTimestamps.size >= MAX_TRACKED_PEERS && oldestFirst.hasNext()) {
            if (oldestFirst.next().lastOrNull()?.let { it >= cutoff } == true) break
            oldestFirst.remove()
        }
        return failureTimestamps.size < MAX_TRACKED_PEERS
    }

    companion object {
        const val MAX_FAILURES_PER_WINDOW = 5
        const val LOCKOUT_WINDOW_MS = 60_000L

        /** Ceiling on the NodeIds holding failure state at once. */
        internal const val MAX_TRACKED_PEERS = 4_096

        /** Consecutive per-connection denials after which the connection is closed. */
        const val MAX_FAILURES_BEFORE_CLOSE = 3
    }
}
