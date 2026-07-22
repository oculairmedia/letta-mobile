package com.letta.mobile.data.controller.reconnect

import kotlin.random.Random

/**
 * Bounded AWS-style full-jitter backoff: the delay for attempt N is drawn
 * uniformly from [0, min(maxDelayMs, baseDelayMs * 2^N)]. Full jitter (rather
 * than exponential-plus-fractional-jitter) spreads simultaneous reconnecting
 * clients across the whole window, which matters when one App Server restart
 * drops every controller at once.
 *
 * @param baseDelayMs Cap for attempt 0.
 * @param maxDelayMs Absolute delay cap.
 * @param maxAttempts Attempts allowed before [delayFor] reports exhaustion.
 * @param random Injectable source so tests are deterministic.
 */
class FullJitterBackoff(
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val random: Random = Random.Default,
) {
    init {
        require(baseDelayMs > 0) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be >= baseDelayMs" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    /**
     * Delay before retry [attempt] (0-based), or null when the attempt budget
     * is exhausted and the caller must stop retrying.
     */
    fun delayFor(attempt: Int): Long? {
        if (attempt >= maxAttempts) return null
        val exponent = attempt.coerceAtMost(MAX_EXPONENT)
        val cap = (baseDelayMs shl exponent).coerceIn(baseDelayMs, maxDelayMs)
        return random.nextLong(cap + 1)
    }

    companion object {
        const val DEFAULT_BASE_DELAY_MS = 1_000L
        const val DEFAULT_MAX_DELAY_MS = 30_000L
        const val DEFAULT_MAX_ATTEMPTS = 10

        // shl beyond 62 overflows Long; any realistic cap saturates far earlier.
        private const val MAX_EXPONENT = 30
    }
}
