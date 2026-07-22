package com.letta.mobile.data.controller.reconnect

import kotlin.math.min
import kotlin.random.Random

/**
 * Bounded full-jitter backoff for App Server reconnect (letta-mobile-lgns8.5).
 *
 * Computes the delay before reconnect attempt N using the AWS "full jitter"
 * strategy: an exponentially growing ceiling capped at [maxDelayMs], with the
 * actual delay chosen uniformly in `[0, ceiling]`. Full jitter avoids the
 * thundering-herd/synchronized-retry problem that plain exponential backoff
 * causes when many clients reconnect to a restarted server at once.
 *
 * Pure and deterministic given an injected [Random], so the reconnect
 * supervisor's timing is fully testable under virtual time.
 *
 * @param baseDelayMs delay ceiling for the first retry (attempt 0).
 * @param maxDelayMs hard cap on the ceiling regardless of attempt count.
 * @param multiplier exponential growth factor per attempt (>= 1.0).
 */
class FullJitterBackoff(
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val multiplier: Double = DEFAULT_MULTIPLIER,
) {
    init {
        require(baseDelayMs > 0) { "baseDelayMs must be > 0, was $baseDelayMs" }
        require(maxDelayMs >= baseDelayMs) {
            "maxDelayMs ($maxDelayMs) must be >= baseDelayMs ($baseDelayMs)"
        }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0, was $multiplier" }
    }

    /**
     * The exponential delay ceiling for [attempt] (0-based), capped at
     * [maxDelayMs]. Deterministic and jitter-free — exposed for testing the
     * growth curve independently of the RNG.
     */
    fun ceilingMs(attempt: Int): Long {
        require(attempt >= 0) { "attempt must be >= 0, was $attempt" }
        // Grow multiplicatively but guard against Double overflow to +Inf for
        // large attempts by clamping to maxDelayMs as soon as we exceed it.
        var ceiling = baseDelayMs.toDouble()
        repeat(attempt) {
            ceiling *= multiplier
            if (ceiling >= maxDelayMs.toDouble()) return maxDelayMs
        }
        return min(ceiling.toLong(), maxDelayMs)
    }

    /**
     * The actual (jittered) delay in ms before [attempt]: a value chosen
     * uniformly in `[0, ceilingMs(attempt)]`.
     */
    fun delayMs(attempt: Int, random: Random = Random): Long {
        val ceiling = ceilingMs(attempt)
        // nextLong(until) requires until > 0; a zero ceiling can't happen given
        // baseDelayMs > 0, but guard for safety.
        return if (ceiling <= 0) 0 else random.nextLong(ceiling + 1)
    }

    companion object {
        const val DEFAULT_BASE_DELAY_MS: Long = 250
        const val DEFAULT_MAX_DELAY_MS: Long = 30_000
        const val DEFAULT_MULTIPLIER: Double = 2.0
    }
}
