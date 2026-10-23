package com.letta.mobile.data.controller.node.agentfiles

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentRosterCacheTest {
    private var signature: Long? = 1L
    private var nowMs = 0L
    private var fetches = 0
    private var windowFetches = 0

    private fun roster(vararg ids: String) = JsonArray(ids.map { JsonPrimitive(it) })

    private fun cache(maxAgeMs: Long = 30_000L, onTooLarge: (Int) -> Unit = {}) =
        AgentRosterCache(signature = { signature }, maxAgeMs = maxAgeMs, clock = { nowMs }, onTooLarge = onTooLarge)

    private suspend fun AgentRosterCache.read(result: JsonArray? = roster("a")): JsonArray? = roster(
        fetchAll = { fetches++; result },
        fetchWindow = { windowFetches++; roster("window") },
    )

    @Test
    fun anUnchangedStoreIsServedFromOneFetch() = runTest {
        val cache = cache()
        repeat(5) { cache.read() }

        assertEquals(1, fetches)
    }

    @Test
    fun aChangedSignatureFetchesAgain() = runTest {
        val cache = cache()
        cache.read(roster("a"))
        signature = 2L

        assertEquals(roster("a", "b"), cache.read(roster("a", "b")))
        assertEquals(2, fetches)
    }

    @Test
    fun aRosterOlderThanTheCeilingIsFetchedAgain() = runTest {
        val cache = cache(maxAgeMs = 1_000L)
        cache.read()
        nowMs = 999L
        cache.read()
        nowMs = 1_000L
        cache.read()

        assertEquals(2, fetches)
    }

    @Test
    fun invalidateDropsTheHeldRoster() = runTest {
        val cache = cache()
        cache.read()
        cache.invalidate()
        cache.read()

        assertEquals(2, fetches)
    }

    @Test
    fun aFetchRunningWhenInvalidatedIsServedButNotHeld() = runTest {
        val cache = cache()
        val release = CompletableDeferred<Unit>()
        val stale = async { cache.roster({ fetches++; release.await(); roster("before-write") }, { roster("window") }) }
        yield()
        cache.invalidate()
        release.complete(Unit)

        assertEquals(roster("before-write"), stale.await())
        assertEquals(roster("after-write"), cache.read(roster("after-write")))
        assertEquals(2, fetches)
    }

    @Test
    fun concurrentMissesShareOneFetch() = runTest {
        val cache = cache()
        val release = CompletableDeferred<Unit>()
        val first = async { cache.roster({ fetches++; release.await(); roster("a") }, { roster("window") }) }
        val second = async { cache.roster({ fetches++; roster("never") }, { roster("window") }) }
        yield()
        release.complete(Unit)

        assertEquals(roster("a"), first.await())
        assertEquals(roster("a"), second.await())
        assertEquals(1, fetches)
    }

    @Test
    fun aFailedFetchIsNotHeld() = runTest {
        val cache = cache()

        assertNull(cache.read(result = null))
        cache.read()

        assertEquals(2, fetches)
    }

    @Test
    fun withoutASignatureEveryReadFetchesOnlyTheWindow() = runTest {
        signature = null
        val cache = cache()
        repeat(3) { assertEquals(roster("window"), cache.read()) }

        assertEquals(0, fetches)
        assertEquals(3, windowFetches)
    }

    @Test
    fun aRosterPastTheCapFallsBackToWindowFetchesUntilTheStoreChanges() = runTest {
        val reports = mutableListOf<Int>()
        val cache = cache(onTooLarge = { reports += it })
        val big = JsonArray(List(AgentRosterCache.MAX_AGENTS + 1) { JsonPrimitive(it) })

        assertEquals(big, cache.read(big))
        assertEquals(roster("window"), cache.read(big))
        signature = 2L
        cache.read(roster("a"))
        cache.read(roster("a"))

        assertEquals(listOf(big.size), reports)
        assertEquals(2, fetches, "one too-large fetch, then one full fetch after the change")
        assertEquals(1, windowFetches)
    }
}
