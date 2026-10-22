package com.letta.mobile.data.controller.node.listcache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HeldListCacheTest {
    private var signature: Long? = 1L
    private var signatureReads = 0
    private var nowMs = 0L
    private var fetches = 0
    private var ownFetches = 0

    private fun list(vararg ids: String) = JsonArray(ids.map { JsonPrimitive(it) })

    private fun cache(
        limits: HeldListLimits = HeldListLimits(maxItems = 10, recheckMs = 0L),
        onTooLarge: (Int, Int) -> Unit = { _, _ -> },
    ) = HeldListCache(
        signature = { signatureReads++; signature },
        limits = limits,
        clock = { nowMs },
        onTooLarge = onTooLarge,
    )

    /** Serves the full list as is, or "own" when the cache falls back to the caller's fetch. */
    private suspend fun HeldListCache.read(result: JsonArray? = list("a")): JsonArray? = serve(
        fetchAll = { fetches++; result },
        fromFull = { it },
        fetchOwn = { ownFetches++; list("own") },
    )

    @Test
    fun anUnchangedStoreIsServedFromOneFetch() = runTest {
        val cache = cache()
        repeat(5) { cache.read() }

        assertEquals(1, fetches)
    }

    @Test
    fun theCallersSliceIsCutFromTheFullList() = runTest {
        val cache = cache()

        val sliced = cache.serve({ list("a", "b", "c") }, { JsonArray(it.drop(1)) }, { list("own") })

        assertEquals(list("b", "c"), sliced)
    }

    @Test
    fun aChangedSignatureFetchesAgain() = runTest {
        val cache = cache()
        cache.read(list("a"))
        signature = 2L

        assertEquals(list("a", "b"), cache.read(list("a", "b")))
        assertEquals(2, fetches)
    }

    @Test
    fun aSignatureReadWithinTheRecheckWindowIsReused() = runTest {
        val cache = cache(HeldListLimits(maxItems = 10, recheckMs = 1_000L))
        cache.read()
        signature = 2L
        nowMs = 999L
        cache.read()
        assertEquals(1, fetches, "a change inside the window is seen at the next read after it")
        assertEquals(1, signatureReads)

        nowMs = 1_000L
        cache.read()

        assertEquals(2, fetches)
        assertEquals(2, signatureReads)
    }

    @Test
    fun aListOlderThanTheCeilingIsFetchedAgain() = runTest {
        val cache = cache(HeldListLimits(maxItems = 10, maxAgeMs = 1_000L, recheckMs = 0L))
        cache.read()
        nowMs = 999L
        cache.read()
        nowMs = 1_000L
        cache.read()

        assertEquals(2, fetches)
    }

    @Test
    fun invalidateDropsTheHeldList() = runTest {
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
        val stale = async { cache.serve({ fetches++; release.await(); list("before-write") }, { it }, { list("own") }) }
        yield()
        cache.invalidate()
        release.complete(Unit)

        assertEquals(list("before-write"), stale.await())
        assertEquals(list("after-write"), cache.read(list("after-write")))
        assertEquals(2, fetches)
    }

    @Test
    fun concurrentMissesShareOneFetch() = runTest {
        val cache = cache()
        val release = CompletableDeferred<Unit>()
        val first = async { cache.serve({ fetches++; release.await(); list("a") }, { it }, { list("own") }) }
        val second = async { cache.serve({ fetches++; list("never") }, { it }, { list("own") }) }
        yield()
        release.complete(Unit)

        assertEquals(list("a"), first.await())
        assertEquals(list("a"), second.await())
        assertEquals(1, fetches)
    }

    @Test
    fun aFailedFullFetchAnswersWithTheCallersFetchAndBacksOff() = runTest {
        val cache = cache()

        assertEquals(list("own"), cache.read(result = null))
        nowMs = HeldListCache.FAILURE_BACKOFF_MS - 1
        assertEquals(list("own"), cache.read())
        assertEquals(1, fetches, "no full fetch inside the backoff")

        nowMs = HeldListCache.FAILURE_BACKOFF_MS
        assertEquals(list("a"), cache.read())
        assertEquals(2, fetches)
        assertEquals(2, ownFetches)
    }

    @Test
    fun withoutASignatureEveryReadUsesTheCallersOwnFetch() = runTest {
        signature = null
        val cache = cache()
        repeat(3) { assertEquals(list("own"), cache.read()) }

        assertEquals(0, fetches)
        assertEquals(3, ownFetches)
    }

    @Test
    fun aListPastTheCapFallsBackToTheCallersFetchUntilTheStoreChanges() = runTest {
        val reports = mutableListOf<Pair<Int, Int>>()
        val cache = cache(onTooLarge = { items, max -> reports += items to max })
        val big = JsonArray(List(11) { JsonPrimitive(it) })

        assertEquals(list("own"), cache.read(big), "a list too long to hold is never answered from")
        assertEquals(list("own"), cache.read(big))
        signature = 2L
        cache.read(list("a"))
        cache.read(list("a"))

        assertEquals(listOf(11 to 10), reports)
        assertEquals(2, fetches, "one too-large fetch, then one full fetch after the change")
        assertEquals(2, ownFetches)
    }
}
