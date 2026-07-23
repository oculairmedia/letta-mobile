package com.letta.mobile.data.controller.node.iroh

import java.security.MessageDigest

/**
 * Hardened bootstrap bearer verification for Iroh connections
 * (letta-mobile-d6e8g.3): constant-time token comparison, per-NodeId failure
 * rate limiting with a lockout window, and outcomes that never carry the
 * provided or expected secret.
 *
 * Rate-limit state is scoped to the verifier instance; production shares one
 * instance per endpoint (every accepted connection of an [IrohNodeEndpoint]),
 * so redialing cannot reset a peer's failure budget.
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

    private val failureTimestamps = mutableMapOf<String, ArrayDeque<Long>>()
    private val lock = Any()

    fun verify(remoteEndpointId: String, providedToken: String?): Outcome {
        val expected = policy.requiredBearerToken
            ?: return Outcome.Authenticated

        if (isRateLimited(remoteEndpointId)) return Outcome.RateLimited

        val provided = providedToken
        val matches = provided != null &&
            MessageDigest.isEqual(
                expected.encodeToByteArray(),
                provided.encodeToByteArray(),
            )
        return if (matches) {
            Outcome.Authenticated
        } else {
            recordFailure(remoteEndpointId)
            if (isRateLimited(remoteEndpointId)) {
                Outcome.RateLimited
            } else {
                Outcome.Denied(if (provided.isNullOrBlank()) "missing_token" else "invalid_token")
            }
        }
    }

    private fun isRateLimited(remoteEndpointId: String): Boolean = synchronized(lock) {
        val cutoff = nowMs() - lockoutWindowMs
        val failures = failureTimestamps[remoteEndpointId] ?: return false
        while (failures.isNotEmpty() && failures.first() < cutoff) failures.removeFirst()
        if (failures.isEmpty()) failureTimestamps.remove(remoteEndpointId)
        failures.size >= maxFailuresPerWindow
    }

    private fun recordFailure(remoteEndpointId: String) {
        synchronized(lock) {
            val failures = failureTimestamps.getOrPut(remoteEndpointId) { ArrayDeque() }
            failures.addLast(nowMs())
            // Bound memory per peer: older entries beyond the limit can never
            // matter once the window check has run.
            while (failures.size > maxFailuresPerWindow) failures.removeFirst()
        }
    }

    companion object {
        const val MAX_FAILURES_PER_WINDOW = 5
        const val LOCKOUT_WINDOW_MS = 60_000L

        /** Consecutive per-connection denials after which the connection is closed. */
        const val MAX_FAILURES_BEFORE_CLOSE = 3
    }
}
