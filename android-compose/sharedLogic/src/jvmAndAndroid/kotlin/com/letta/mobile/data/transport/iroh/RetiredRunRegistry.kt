package com.letta.mobile.data.transport.iroh

import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded, connection-scoped duplicate-terminal guard for runs that have
 * already retired. Content and tool frames remain observable after retirement
 * because only `stop_reason` is suppressed.
 */
internal class RetiredRunRegistry(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val retiredAtByRunId = ConcurrentHashMap<String, Long>()

    fun record(runId: String) {
        if (runId.isBlank()) return
        prune()
        retiredAtByRunId[runId] = clockMillis()
    }

    fun shouldSuppressTerminal(runId: String?, messageType: String?): Boolean =
        messageType == STOP_REASON && runId != null && retiredAtByRunId.containsKey(runId)

    fun clear() {
        retiredAtByRunId.clear()
    }

    private fun prune() {
        val cutoff = clockMillis() - RETENTION_MS
        retiredAtByRunId.entries.removeIf { (_, retiredAt) -> retiredAt < cutoff }
        retiredAtByRunId.entries
            .sortedBy { it.value }
            .take((retiredAtByRunId.size - MAX_RETIRED_RUNS).coerceAtLeast(0))
            .forEach { (runId) -> retiredAtByRunId.remove(runId) }
    }

    private companion object {
        const val STOP_REASON = "stop_reason"
        const val RETENTION_MS = 60_000L
        const val MAX_RETIRED_RUNS = 256
    }
}
