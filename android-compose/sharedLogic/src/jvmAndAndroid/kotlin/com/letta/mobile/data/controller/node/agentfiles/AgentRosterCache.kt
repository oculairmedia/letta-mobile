package com.letta.mobile.data.controller.node.agentfiles

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray

/**
 * The App Server's full agent roster, fetched once and reused while it cannot have changed, so
 * paging through `agent.list` costs one fetch rather than one per page.
 *
 * A held roster is served while three things hold: [signature] (the files the App Server builds
 * each row from: the agent file and the agent's default conversation) is what it was before the
 * fetch, nothing called [invalidate] since that fetch started, and it is younger than [maxAgeMs].
 * The age bound covers what no file shows, such as defaults the App Server derives from its model
 * catalog, and a change the files show before the App Server serves it. It also bounds the harm if
 * [signature] watches a directory the App Server does not write. Callers waiting on a miss share one
 * fetch.
 *
 * When nothing can be held (no [signature], or the last full roster was longer than [MAX_AGENTS],
 * which [onTooLarge] is told), the caller's window fetch is used instead, as before the cache.
 */
internal class AgentRosterCache(
    private val signature: () -> Long?,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    /** Monotonic milliseconds, so a wall-clock step cannot stretch the age bound. */
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val onTooLarge: (agents: Int) -> Unit = {},
) {
    private class Held(val signature: Long, val fetchedAtMs: Long, val roster: JsonArray)

    @Volatile private var held: Held? = null

    // Guards the generation check and the assignment of [held] together, so an invalidate cannot land
    // between them and leave a pre-write roster held.
    private val lock = Any()
    private var generation = 0L

    private val fetching = Mutex()

    /** The signature under which the full roster was last found too long to hold; null when it was not. */
    @Volatile private var tooLargeAt: Long? = null

    /** Drops the held roster; a fetch already running when this is called is not held either. */
    fun invalidate() {
        synchronized(lock) {
            generation++
            held = null
        }
    }

    /**
     * The roster, or a prefix of it: the held roster when one can be served, else [fetchAll]'s, or
     * [fetchWindow]'s when nothing can be held. Null when the fetch fails.
     */
    suspend fun roster(fetchAll: suspend () -> JsonArray?, fetchWindow: suspend () -> JsonArray?): JsonArray? {
        val before = signature() ?: return fetchWindow()
        if (tooLargeAt == before) return fetchWindow()
        servable(before)?.let { return it }
        return fetching.withLock {
            servable(before)?.let { return@withLock it }
            val startedAt = synchronized(lock) { generation }
            val fetchedAtMs = clock()
            val fetched = fetchAll() ?: return@withLock null
            if (fetched.size > MAX_AGENTS) {
                tooLargeAt = before
                onTooLarge(fetched.size)
            } else {
                synchronized(lock) {
                    if (startedAt == generation) held = Held(before, fetchedAtMs, fetched)
                }
            }
            fetched
        }
    }

    private fun servable(current: Long): JsonArray? =
        held?.takeIf { it.signature == current && clock() - it.fetchedAtMs < maxAgeMs }?.roster

    companion object {
        /** Ceiling on how long a roster is reused when nothing on disk shows a change. */
        const val DEFAULT_MAX_AGE_MS: Long = 30_000L

        /** Rosters past this are not held: about 17 KB per agent row, so roughly 34 MB at the cap. */
        const val MAX_AGENTS: Int = 2_000

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
