package com.letta.mobile.data.plugin.host

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * When a crashing plugin is restarted, and when it is given up on: [maxCrashes] crashes within
 * [window] fault it; before that each restart waits a full-jitter delay, uniform in
 * `[0, min(maxDelay, baseDelay * 2^(crash - 1))]`.
 */
data class CrashPolicy(
    val maxCrashes: Int = 3,
    val window: Duration = 10.minutes,
    val baseDelay: Duration = 500.milliseconds,
    val maxDelay: Duration = 30.seconds,
)

/** The crashes one driver has seen, held to a [CrashPolicy]'s window. */
internal class CrashLedger(
    private val policy: CrashPolicy,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val crashes = ArrayDeque<Long>()

    /** Records a crash now; answers how many fall inside the window, this one included. */
    fun record(): Int {
        val now = nowMillis()
        crashes.addLast(now)
        while (crashes.isNotEmpty() && now - crashes.first() > policy.window.inWholeMilliseconds) crashes.removeFirst()
        return crashes.size
    }

    fun exhausted(count: Int): Boolean = count >= policy.maxCrashes

    /** The full-jitter wait before restart number [crash]. */
    fun delayFor(crash: Int): Duration {
        val shift = (crash - 1).coerceIn(0, MAX_SHIFT)
        val ceiling = minOf(policy.maxDelay.inWholeMilliseconds, policy.baseDelay.inWholeMilliseconds shl shift)
        return random.nextLong(0, ceiling + 1).milliseconds
    }

    private companion object {
        const val MAX_SHIFT = 20
    }
}
