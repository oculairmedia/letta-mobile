package com.letta.mobile.data.controller.node.listcache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray

/** How long and how large a [HeldListCache] holds, and how often it re-reads its signature. */
internal data class HeldListLimits(
    /** Lists longer than this are served but not held. */
    val maxItems: Int,
    /** Ceiling on how long a list is reused when nothing on disk shows a change. */
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    /** A signature read this recently is reused, so a burst of requests reads the disk once. */
    val recheckMs: Long = DEFAULT_RECHECK_MS,
) {
    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 30_000L
        const val DEFAULT_RECHECK_MS: Long = 1_000L
    }
}

/**
 * One full list from the App Server, fetched once and reused while it cannot have changed, so
 * serving many pages or many per-agent slices of it costs one fetch rather than one per request.
 *
 * A held list is served while three things hold: [signature] (the files the App Server builds the
 * rows from) is what it was before the fetch, nothing called [invalidate] since that fetch started,
 * and it is younger than [HeldListLimits.maxAgeMs]. The signature itself is re-read at most once per
 * [HeldListLimits.recheckMs], so a change made outside this host shows within that much longer. The
 * age bound covers what no file shows, a change the files show before the App Server serves it,
 * and a [signature] that watches a directory the App Server does not write. Callers waiting on a
 * miss share one fetch.
 *
 * When nothing can be held (no [signature], or the last full list was longer than
 * [HeldListLimits.maxItems], which [onTooLarge] is told), the caller's own fetch is used instead,
 * as before the cache.
 */
internal class HeldListCache(
    private val signature: () -> Long?,
    private val limits: HeldListLimits,
    /** Monotonic milliseconds, so a wall-clock step cannot stretch the age bound. */
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val onTooLarge: (items: Int, max: Int) -> Unit = { _, _ -> },
) {
    private class Held(val signature: Long, val fetchedAtMs: Long, val list: JsonArray)

    private class Checked(val signature: Long?, val atMs: Long)

    @Volatile private var held: Held? = null

    @Volatile private var lastChecked: Checked? = null

    // Guards the generation check and the assignment of [held] together, so an invalidate cannot land
    // between them and leave a pre-write list held.
    private val lock = Any()
    private var generation = 0L

    private val fetching = Mutex()

    /** The signature under which the full list was last found too long to hold; null when it was not. */
    @Volatile private var tooLargeAt: Long? = null

    /** Drops the held list; a fetch already running when this is called is not held either. */
    fun invalidate() {
        synchronized(lock) {
            generation++
            held = null
        }
    }

    /**
     * The caller's answer: [fromFull] applied to the held or freshly fetched full list ([fetchAll]),
     * or [fetchOwn]'s result when nothing can be held ([fetchOwn] is what the caller fetched before
     * the cache). Null when a fetch fails.
     */
    suspend fun serve(
        fetchAll: suspend () -> JsonArray?,
        fromFull: (JsonArray) -> JsonArray,
        fetchOwn: suspend () -> JsonArray?,
    ): JsonArray? = when (val before = currentSignature()) {
        null, tooLargeAt -> fetchOwn()
        else -> (servable(before) ?: fetchAndHold(before, fetchAll))?.let(fromFull)
    }

    private suspend fun fetchAndHold(before: Long, fetchAll: suspend () -> JsonArray?): JsonArray? =
        fetching.withLock {
            servable(before)?.let { return@withLock it }
            val startedAt = synchronized(lock) { generation }
            val fetchedAtMs = clock()
            val fetched = fetchAll() ?: return@withLock null
            if (fetched.size > limits.maxItems) {
                tooLargeAt = before
                onTooLarge(fetched.size, limits.maxItems)
            } else {
                synchronized(lock) {
                    if (startedAt == generation) held = Held(before, fetchedAtMs, fetched)
                }
            }
            fetched
        }

    private fun currentSignature(): Long? {
        val now = clock()
        lastChecked?.takeIf { now - it.atMs < limits.recheckMs }?.let { return it.signature }
        return signature().also { lastChecked = Checked(it, now) }
    }

    private fun servable(current: Long): JsonArray? =
        held?.takeIf { it.signature == current && clock() - it.fetchedAtMs < limits.maxAgeMs }?.list

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
