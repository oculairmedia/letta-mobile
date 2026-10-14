package com.letta.mobile.data.plugin.host

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** At most [amount] units (calls, bytes) per plugin in any trailing [window]. */
data class PluginRateLimit(val amount: Long, val window: Duration = 1.seconds) {
    init {
        require(amount >= 0) { "a rate limit's amount is never negative" }
        require(window.isPositive()) { "a rate limit's window is positive" }
    }
}

/**
 * How often each plugin may call `host.emit` and `host.log` (plan: trust minimum, letta-mobile-cdgpr.5):
 * [emit] and [log] are per plugin, over a sliding window, so a burst cannot dodge the limit by
 * straddling a second boundary. One instance may be shared by every plugin of a host; each is
 * counted on its own. In memory: a restarted host starts every plugin afresh, which is fine at a
 * one-second window.
 */
class PluginRateLimits(
    val emit: PluginRateLimit = PluginRateLimit(DEFAULT_EMITS_PER_SECOND),
    val log: PluginRateLimit = PluginRateLimit(DEFAULT_LOGS_PER_SECOND),
    nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val emits = SlidingWindowCounter(emit, nowMs)
    private val logs = SlidingWindowCounter(log, nowMs)

    /** Counts one emit of [pluginId]; false (and not counted) when it is over [emit]. */
    fun tryEmit(pluginId: String): Boolean = emits.tryTake(pluginId, 1)

    /** Counts one log line of [pluginId]; false (and not counted) when it is over [log]. */
    fun tryLog(pluginId: String): Boolean = logs.tryTake(pluginId, 1)

    companion object {
        const val DEFAULT_EMITS_PER_SECOND: Long = 10
        const val DEFAULT_LOGS_PER_SECOND: Long = 50
    }
}

/**
 * A sliding-window budget per key: each take is remembered with its time and weight until it is
 * [PluginRateLimit.window] old, and a take that would put the remembered total over
 * [PluginRateLimit.amount] is refused and not remembered.
 */
internal class SlidingWindowCounter(private val limit: PluginRateLimit, private val nowMs: () -> Long) {
    private class Take(val atMs: Long, val weight: Long)

    private val lock = SynchronizedObject()
    private val takes = mutableMapOf<String, ArrayDeque<Take>>()
    private val windowMs = limit.window.inWholeMilliseconds

    fun tryTake(key: String, weight: Long): Boolean = synchronized(lock) {
        val now = nowMs()
        val history = takes.getOrPut(key) { ArrayDeque() }
        while (history.isNotEmpty() && history.first().atMs <= now - windowMs) history.removeFirst()
        if (history.sumOf { it.weight } + weight > limit.amount) return@synchronized false
        history.addLast(Take(now, weight))
        true
    }

    /** Gives back the most recent take of [weight] for [key] (the work it paid for did not happen). */
    fun refund(key: String, weight: Long): Unit = synchronized(lock) {
        val history = takes[key] ?: return@synchronized
        val index = history.indexOfLast { it.weight == weight }
        if (index >= 0) history.removeAt(index)
    }

    /** What [key] has taken in the current window. */
    fun used(key: String): Long = synchronized(lock) {
        val now = nowMs()
        takes[key].orEmpty().filter { it.atMs > now - windowMs }.sumOf { it.weight }
    }
}
