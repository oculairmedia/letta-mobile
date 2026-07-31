package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerExternalToolResult
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Bounded, expiring cache of computed external-tool results (lgns8.22.4.1.6).
 *
 * WHY IT OUTLIVES A SUCCESSFUL SEND
 * `external_tool_call_response` is a ONE-WAY frame: `sendExternalToolResponse`
 * returning without throwing proves only that the bytes left this process — an
 * AmbiguousMutation. If the connection died in flight the App Server never saw
 * the response and re-emits the still-blocking request on reconnect
 * (`sync(recoverApprovals = true)`). Dropping the cached result at send time
 * therefore means the replay RE-INVOKES a possibly non-idempotent tool. The
 * result is instead retained past the send and reused by any replay.
 *
 * WHY IT STILL EXPIRES
 * The server may never replay (process restart, abandoned turn), so retention
 * cannot be unconditional. Two independent bounds apply, both enforced lazily on
 * every access and eagerly on definitive generation cleanup ([pruneExpired]):
 * - [ttlMs]: an entry older than the replay horizon is an orphan and is dropped.
 * - [maxEntries]: oldest-first eviction, so a pathological workload cannot grow
 *   the cache regardless of TTL.
 *
 * Both eviction paths emit telemetry (repo convention: bounded collections
 * report what they drop).
 *
 * Keyed by (request_id, tool_call_id) — the App Server v2 idempotency key for
 * `external_tool_call_request` — and deliberately NOT by connection generation,
 * because the whole point is surviving a generation rollover.
 */
class ExternalToolResultCache(
    private val maxEntries: Int = MAX_ENTRIES,
    private val ttlMs: Long = ORPHAN_TTL_MS,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<Key, CachedResult>()

    data class Key(val requestId: String, val toolCallId: String?)

    private data class CachedResult(
        val result: AppServerExternalToolResult,
        val storedAtMs: Long,
    )

    fun get(key: Key): AppServerExternalToolResult? = synchronized(lock) {
        pruneExpiredLocked()
        entries[key]?.result
    }

    fun put(key: Key, result: AppServerExternalToolResult) {
        synchronized(lock) {
            pruneExpiredLocked()
            entries.remove(key)
            entries[key] = CachedResult(result = result, storedAtMs = nowMs())
            while (entries.size > maxEntries) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
                Telemetry.event(
                    "ExternalToolResultCache",
                    "result.evictedOverCap",
                    "requestId" to oldest.requestId,
                    "toolCallId" to (oldest.toolCallId ?: ""),
                    "cap" to maxEntries,
                    level = Telemetry.Level.WARN,
                )
            }
        }
    }

    /**
     * Drop orphaned results. Called on definitive generation/turn cleanup as well
     * as lazily from [get]/[put].
     */
    fun pruneExpired() {
        synchronized(lock) { pruneExpiredLocked() }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun contains(key: Key): Boolean = synchronized(lock) { entries.containsKey(key) }

    private fun pruneExpiredLocked() {
        if (entries.isEmpty()) return
        val cutoff = nowMs() - ttlMs
        // Bounded by maxEntries, so a full scan is cheap and does not rely on
        // insertion order tracking wall-clock order.
        val expired = entries.entries
            .filter { it.value.storedAtMs <= cutoff }
            .map { it.key }
        if (expired.isEmpty()) return
        expired.forEach { entries.remove(it) }
        Telemetry.event(
            "ExternalToolResultCache",
            "result.orphanExpired",
            "count" to expired.size,
            "ttlMs" to ttlMs,
            level = Telemetry.Level.WARN,
        )
    }

    companion object {
        /** Bound on retained results regardless of TTL. */
        const val MAX_ENTRIES = 64

        /**
         * Replay horizon. A reconnect + `sync(recoverApprovals = true)` replay
         * happens within seconds; anything still cached after this is an orphan
         * the server will never ask for again.
         */
        const val ORPHAN_TTL_MS = 10 * 60 * 1000L
    }
}
