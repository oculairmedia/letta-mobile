package com.letta.mobile.util

import com.letta.mobile.data.repository.exhaustCursorPages
import com.letta.mobile.data.repository.exhaustPages
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Seeded property tests for offset and cursor exhaust helpers.
 * Expected results are derived from the in-memory server schedule (not a copy of production loops).
 */
class PaginationHelpersFuzzTest {
    @Test
    fun seededOffsetExhaustMatchesScheduleExpectation() = runTest {
        var cases = 0
        FuzzCorpus.paginationSeeds.forEach { seed ->
            repeat(FuzzCorpus.SCENARIOS_PER_SEED) { scenario ->
                cases++
                val ctx = FuzzScenarioContext(seed, scenario)
                val random = scenarioRandom(seed xor OFFSET_XOR, scenario)
                val pageSize = random.nextInt(1, 12)
                val itemCount = random.nextInt(0, 80)
                val items = List(itemCount) { index -> "item-$seed-$scenario-$index" }
                val ignoreOffsetAfter = random.nextInt(3, 15)
                val maxPages = 30
                val server = OffsetServer(items, ignoreOffsetAfter)
                val merged = exhaustPages(
                    pageSize = pageSize,
                    maxPages = maxPages,
                    fetch = { limit, offset -> server.fetch(limit, offset) },
                    dedupKey = { it },
                )
                val (expected, scheduleFetches) = expectedPrefix(items, pageSize, maxPages, ignoreOffsetAfter)
                ctx.check(condition = merged == expected) {
                    "offset exhaust mismatch size=${merged.size} expected=${expected.size} fetches=${server.fetchCount}"
                }
                ctx.check(condition = merged.size == merged.distinct().size) {
                    "duplicate keys in offset exhaust"
                }
                ctx.check(condition = server.fetchCount == scheduleFetches) {
                    "offset fetch count ${server.fetchCount} != schedule $scheduleFetches"
                }
            }
        }
        check(cases == FuzzCorpus.paginationSeeds.size * FuzzCorpus.SCENARIOS_PER_SEED)
    }

    @Test
    fun seededCursorExhaustMatchesScheduleExpectation() = runTest {
        var cases = 0
        FuzzCorpus.paginationSeeds.forEach { seed ->
            repeat(FuzzCorpus.SCENARIOS_PER_SEED) { scenario ->
                cases++
                val ctx = FuzzScenarioContext(seed, scenario)
                val random = scenarioRandom(seed xor CURSOR_XOR, scenario)
                val pageSize = random.nextInt(1, 12)
                val itemCount = random.nextInt(0, 80)
                val items = List(itemCount) { index -> Record("k-$seed-$scenario-$index") }
                val stickyCursorAfter = random.nextInt(2, 12)
                val maxPages = 30
                val server = CursorServer(items, stickyCursorAfter)
                val merged = exhaustCursorPages(
                    pageSize = pageSize,
                    maxPages = maxPages,
                    fetch = { limit, after -> server.fetch(limit, after) },
                    itemKey = { it.key },
                )
                val (expected, scheduleFetches) = expectedPrefix(items, pageSize, maxPages, stickyCursorAfter)
                ctx.check(condition = merged == expected) {
                    "cursor exhaust mismatch fetches=${server.fetchCount} schedule=$scheduleFetches"
                }
                ctx.check(condition = merged.size == merged.distinctBy { it.key }.size) {
                    "duplicate keys in cursor exhaust"
                }
                ctx.check(condition = server.fetchCount == scheduleFetches) {
                    "cursor fetch count ${server.fetchCount} != schedule $scheduleFetches"
                }
            }
        }
        check(cases == FuzzCorpus.paginationSeeds.size * FuzzCorpus.SCENARIOS_PER_SEED)
    }

    /**
     * Both fixtures supply unique consecutive pages before repeating an already-read page.
     * Compute the reachable prefix and terminal request algebraically, without a client loop.
     * An exact full final page needs one empty request, as does an initially empty store.
     */
    private fun <T> expectedPrefix(
        items: List<T>,
        pageSize: Int,
        maxPages: Int,
        successfulPages: Int,
    ): Pair<List<T>, Int> {
        val readablePages = minOf(successfulPages, maxPages)
        val terminalFetch = minOf(items.size / pageSize + 1, successfulPages + 1, maxPages)
        return items.take(readablePages * pageSize) to terminalFetch
    }

    private data class Record(val key: String)

    private class OffsetServer(
        private val items: List<String>,
        private val ignoreOffsetAfterPage: Int,
    ) {
        private var pageCalls = 0
        var fetchCount: Int = 0

        fun fetch(limit: Int, offset: Int): List<String> {
            fetchCount++
            pageCalls++
            if (pageCalls > ignoreOffsetAfterPage) {
                return items.take(limit)
            }
            return items.drop(offset).take(limit)
        }
    }

    private class CursorServer(
        private val items: List<Record>,
        private val stickyAfterPage: Int,
    ) {
        private var pageCalls = 0
        private var stickyKey: String? = null
        var fetchCount: Int = 0

        fun fetch(limit: Int, after: String?): List<Record> {
            fetchCount++
            pageCalls++
            if (pageCalls > stickyAfterPage) {
                val anchor = stickyKey ?: items.firstOrNull()?.key
                stickyKey = anchor
                val start = anchor?.let { key -> items.indexOfFirst { it.key == key } } ?: 0
                return items.drop(start.coerceAtLeast(0)).take(limit)
            }
            val start = after?.let { key -> items.indexOfFirst { it.key == key } + 1 } ?: 0
            val page = items.drop(start.coerceAtLeast(0)).take(limit)
            if (pageCalls == stickyAfterPage) stickyKey = page.firstOrNull()?.key
            return page
        }
    }

    private companion object {
        const val OFFSET_XOR: Long = 0x0FF5E7L
        const val CURSOR_XOR: Long = 0xC05000L
    }
}
