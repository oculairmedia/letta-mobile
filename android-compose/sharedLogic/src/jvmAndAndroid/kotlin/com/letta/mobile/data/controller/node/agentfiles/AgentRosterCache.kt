package com.letta.mobile.data.controller.node.agentfiles

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray

/**
 * The App Server's full agent roster, fetched once and reused while it cannot have changed, so
 * paging through `agent.list` costs one fetch rather than one per page.
 *
 * A held roster is served while three things hold: [signature] (the agent files on disk, which the
 * App Server rewrites whenever an agent's row changes) is what it was before the fetch, nothing
 * called [invalidate] since that fetch started, and it is younger than [maxAgeMs]. The age bound
 * covers a change the files show before the App Server serves it. Callers waiting on a miss share
 * one fetch. A roster longer than [MAX_AGENTS] is served but not held, and [onTooLarge] is told.
 */
internal class AgentRosterCache(
    private val signature: () -> Long?,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onTooLarge: (agents: Int) -> Unit = {},
) {
    private class Held(val signature: Long, val fetchedAtMs: Long, val roster: JsonArray)

    @Volatile private var held: Held? = null

    // Guards the generation check and the assignment of [held] together, so an invalidate cannot land
    // between them and leave a pre-write roster held.
    private val lock = Any()
    private var generation = 0L

    private val fetching = Mutex()

    /** Drops the held roster; a fetch already running when this is called is not held either. */
    fun invalidate() {
        synchronized(lock) {
            generation++
            held = null
        }
    }

    /** The roster, from [fetch] when none can be served; null when [fetch] fails. */
    suspend fun roster(fetch: suspend () -> JsonArray?): JsonArray? {
        val before = signature() ?: return fetch()
        servable(before)?.let { return it }
        return fetching.withLock {
            servable(before)?.let { return@withLock it }
            val startedAt = synchronized(lock) { generation }
            val fetchedAtMs = clock()
            val fetched = fetch() ?: return@withLock null
            if (fetched.size > MAX_AGENTS) {
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
    }
}
