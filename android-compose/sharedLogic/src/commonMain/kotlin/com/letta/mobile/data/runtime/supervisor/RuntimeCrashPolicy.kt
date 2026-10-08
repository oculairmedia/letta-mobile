package com.letta.mobile.data.runtime.supervisor

import kotlin.math.min

/**
 * Restart policy for a supervised local runtime process (letta-mobile-bzvro.3, F03).
 *
 * - Unexpected exits restart after [baseDelayMs] doubling per consecutive crash, capped at
 *   [maxDelayMs] (2 s, 4 s, 8 s, 16 s, 30 s).
 * - [maxCrashesInWindow] crashes inside [crashWindowMs] stop automatic restarts.
 * - A child that stayed up for [healthyUptimeMs] resets the counters before its exit is counted.
 */
data class RuntimeCrashPolicyConfig(
    val baseDelayMs: Long = 2_000,
    val maxDelayMs: Long = 30_000,
    val crashWindowMs: Long = 60_000,
    val maxCrashesInWindow: Int = 5,
    val healthyUptimeMs: Long = 60_000,
)

/** How a child process ended. */
data class RuntimeExit(
    val exitCode: Int?,
    /** We stopped it ourselves (close, force restart, settings change). */
    val intentional: Boolean,
    val signal: String? = null,
)

/** What the supervisor should do about an exit. */
sealed interface RuntimeCrashDecision {
    /** Intentional stop, clean exit (code 0), or the system is suspended: do not count or restart. */
    data class Ignore(val why: String) : RuntimeCrashDecision

    data class Restart(val delayMs: Long, val consecutiveCrashes: Int) : RuntimeCrashDecision

    data class GiveUp(val consecutiveCrashes: Int) : RuntimeCrashDecision
}

/**
 * Pure crash bookkeeping, driven by an injected clock so it runs under virtual time. Not
 * thread-safe: the platform supervisor serialises calls.
 *
 * Suspend awareness (F04): while [suspended] is true an exit is not counted, because sockets
 * and children dying across a laptop sleep are not crashes. [onResumed] clears the counters.
 */
class RuntimeCrashPolicy(private val config: RuntimeCrashPolicyConfig = RuntimeCrashPolicyConfig()) {
    private val crashTimes = ArrayDeque<Long>()
    private var startedAtMs: Long? = null

    var consecutiveCrashes: Int = 0
        private set

    var suspended: Boolean = false
        private set

    /** A child started (or restarted) at [nowMs]. */
    fun onStarted(nowMs: Long) {
        startedAtMs = nowMs
    }

    fun onExit(nowMs: Long, exit: RuntimeExit): RuntimeCrashDecision {
        val uptime = startedAtMs?.let { nowMs - it }
        startedAtMs = null
        return when {
            exit.intentional -> RuntimeCrashDecision.Ignore("intentional stop")
            exit.exitCode == 0 -> RuntimeCrashDecision.Ignore("clean exit")
            suspended -> RuntimeCrashDecision.Ignore("system suspended")
            else -> countCrash(nowMs, uptime)
        }
    }

    private fun countCrash(nowMs: Long, uptime: Long?): RuntimeCrashDecision {
        if (uptime != null && uptime >= config.healthyUptimeMs) reset()
        crashTimes.addLast(nowMs)
        while (crashTimes.isNotEmpty() && nowMs - crashTimes.first() > config.crashWindowMs) crashTimes.removeFirst()
        consecutiveCrashes += 1
        if (crashTimes.size >= config.maxCrashesInWindow) return RuntimeCrashDecision.GiveUp(consecutiveCrashes)
        return RuntimeCrashDecision.Restart(delayFor(consecutiveCrashes), consecutiveCrashes)
    }

    /** Backoff before restart number [crashes] (1-based). */
    fun delayFor(crashes: Int): Long {
        var delay = config.baseDelayMs
        repeat((crashes - 1).coerceAtLeast(0)) {
            delay *= 2
            if (delay >= config.maxDelayMs) return config.maxDelayMs
        }
        return min(delay, config.maxDelayMs)
    }

    /** Force restart, or the user fixed something: forget past crashes. */
    fun reset() {
        crashTimes.clear()
        consecutiveCrashes = 0
    }

    fun onSuspended() {
        suspended = true
    }

    /** Back from sleep: crashes seen around the sleep do not count against the give-up limit. */
    fun onResumed() {
        suspended = false
        reset()
    }
}

/** Keeps the last [capacity] stderr lines of a child, for crash reports and the health card. */
class StderrRing(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lines = ArrayDeque<String>(capacity)

    fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }

    fun snapshot(): List<String> = lines.toList()

    fun clear() = lines.clear()

    companion object {
        const val DEFAULT_CAPACITY = 10
    }
}

/**
 * Health snapshot of a supervised runtime, polled by settings and the chat banner.
 *
 * [generation] increments on every successful (re)start, so a consumer can reconnect to the new
 * child when it changes.
 */
data class RuntimeHealth(
    val active: Boolean = false,
    val consecutiveCrashes: Int = 0,
    val lastExitCode: Int? = null,
    val lastSignal: String? = null,
    val recentStderr: List<String> = emptyList(),
    val restartPending: Boolean = false,
    /** Automatic restart stopped after too many rapid crashes; only Force restart revives it. */
    val gaveUp: Boolean = false,
    val generation: Long = 0,
    val lastError: String? = null,
) {
    /** The runtime is down and nothing will bring it back without the user. */
    val needsForceRestart: Boolean get() = gaveUp || (!active && !restartPending && lastExitCode != null)
}
