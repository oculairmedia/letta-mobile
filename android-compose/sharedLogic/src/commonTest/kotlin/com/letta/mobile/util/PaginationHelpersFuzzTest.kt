package com.letta.mobile.util

import com.letta.mobile.data.repository.exhaustCursorPages
import com.letta.mobile.data.repository.exhaustPages
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seeded property tests for offset and cursor exhaust helpers.
 * Expected results are derived from the in-memory server schedule (not a copy of production loops).
 */
class PaginationHelpersFuzzTest {
    @Test
    fun seededOffsetExhaustMatchesScheduleExpectation() = runTest {
        val expectedCases = FuzzCorpus.paginationSeeds.size * FuzzCorpus.SCENARIOS_PER_SEED
        val cases = runOffsetExhaustCampaign(FuzzCorpus.paginationSeeds, FuzzCorpus.SCENARIOS_PER_SEED)
        assertEquals(expectedCases, cases, "Every generated offset exhaust scenario must run")
    }

    @Test
    fun seededCursorExhaustMatchesScheduleExpectation() = runTest {
        val expectedCases = FuzzCorpus.paginationSeeds.size * FuzzCorpus.SCENARIOS_PER_SEED
        val cases = runCursorExhaustCampaign(FuzzCorpus.paginationSeeds, FuzzCorpus.SCENARIOS_PER_SEED)
        assertEquals(expectedCases, cases, "Every generated cursor exhaust scenario must run")
    }

    companion object {
        private const val OFFSET_XOR: Long = 0x0FF5E7L
        private const val CURSOR_XOR: Long = 0xC05000L

        private enum class ExhaustKind(
            val seedXor: Long,
            val faultPageRange: IntRange,
        ) {
            Offset(OFFSET_XOR, 3..14),
            Cursor(CURSOR_XOR, 2..11),
        }

        private data class ExhaustFixtureParams(
            val pageSize: Int,
            val itemCount: Int,
            val faultAfterPage: Int,
            val maxPages: Int = 30,
        )

        private fun exhaustFixtureParams(random: Random, kind: ExhaustKind): ExhaustFixtureParams =
            ExhaustFixtureParams(
                pageSize = random.nextInt(1, 12),
                itemCount = random.nextInt(0, 80),
                faultAfterPage = random.nextInt(kind.faultPageRange.first, kind.faultPageRange.last),
            )

        private suspend fun runExhaustCampaign(
            seeds: List<Long>,
            scenariosPerSeed: Int,
            kind: ExhaustKind,
            runScenario: suspend (ExhaustKind, FuzzScenarioContext, Random) -> Unit,
        ): Int {
            var cases = 0
            seeds.forEach { seed ->
                repeat(scenariosPerSeed) { scenario ->
                    cases++
                    val random = scenarioRandom(seed xor kind.seedXor, scenario)
                    runScenario(kind, FuzzScenarioContext(seed, scenario), random)
                }
            }
            return cases
        }

        internal suspend fun runOffsetExhaustCampaign(seeds: List<Long>, scenariosPerSeed: Int): Int =
            runExhaustCampaign(seeds, scenariosPerSeed, ExhaustKind.Offset, ::runExhaustScenario)

        internal suspend fun runCursorExhaustCampaign(seeds: List<Long>, scenariosPerSeed: Int): Int =
            runExhaustCampaign(seeds, scenariosPerSeed, ExhaustKind.Cursor, ::runExhaustScenario)

        private data class ExhaustScenarioContext<T>(
            val fuzz: FuzzScenarioContext,
            val params: ExhaustFixtureParams,
            val items: List<T>,
            val merged: List<T>,
            val expected: List<T>,
            val scheduleFetches: Int,
            val fetchCount: Int,
            val familyLabel: String,
            val distinctKeys: Boolean,
        )

        private fun <T> ExhaustScenarioContext<T>.assertMatchesSchedule() {
            fuzz.check(condition = merged == expected) {
                "$familyLabel exhaust mismatch size=${merged.size} expected=${expected.size} fetches=$fetchCount"
            }
            fuzz.check(condition = distinctKeys) {
                "duplicate keys in $familyLabel exhaust"
            }
            fuzz.check(condition = fetchCount == scheduleFetches) {
                "$familyLabel fetch count $fetchCount != schedule $scheduleFetches"
            }
        }

        private suspend fun runExhaustScenario(kind: ExhaustKind, fuzz: FuzzScenarioContext, random: Random) {
            val seed = fuzz.seed
            val scenario = fuzz.scenarioIndex
            val params = exhaustFixtureParams(random, kind)
            when (kind) {
                ExhaustKind.Offset -> {
                    val items = List(params.itemCount) { index -> "item-$seed-$scenario-$index" }
                    val server = OffsetServer(items, params)
                    val merged = exhaustPages(
                        pageSize = params.pageSize,
                        maxPages = params.maxPages,
                        fetch = { limit, offset -> server.fetch(limit, offset) },
                        dedupKey = { it },
                    )
                    val (expected, scheduleFetches) = expectedPrefix(
                        items,
                        params,
                    )
                    ExhaustScenarioContext(
                        fuzz = fuzz,
                        params = params,
                        items = items,
                        merged = merged,
                        expected = expected,
                        scheduleFetches = scheduleFetches,
                        fetchCount = server.fetchCount,
                        familyLabel = "offset",
                        distinctKeys = merged.size == merged.distinct().size,
                    ).assertMatchesSchedule()
                }
                ExhaustKind.Cursor -> {
                    val items = List(params.itemCount) { index -> Record("k-$seed-$scenario-$index") }
                    val server = CursorServer(items, params)
                    val merged = exhaustCursorPages(
                        pageSize = params.pageSize,
                        maxPages = params.maxPages,
                        fetch = { limit, after -> server.fetch(limit, after) },
                        itemKey = { it.key },
                    )
                    val (expected, scheduleFetches) = expectedPrefix(
                        items,
                        params,
                    )
                    ExhaustScenarioContext(
                        fuzz = fuzz,
                        params = params,
                        items = items,
                        merged = merged,
                        expected = expected,
                        scheduleFetches = scheduleFetches,
                        fetchCount = server.fetchCount,
                        familyLabel = "cursor",
                        distinctKeys = merged.size == merged.distinctBy { it.key }.size,
                    ).assertMatchesSchedule()
                }
            }
        }

        /**
         * Both fixtures supply unique consecutive pages before repeating an already-read page.
         * Compute the reachable prefix and terminal request algebraically, without a client loop.
         */
        private fun <T> expectedPrefix(
            items: List<T>,
            params: ExhaustFixtureParams,
        ): Pair<List<T>, Int> {
            val readablePages = minOf(params.faultAfterPage, params.maxPages)
            val terminalFetch = minOf(items.size / params.pageSize + 1, params.faultAfterPage + 1, params.maxPages)
            return items.take(readablePages * params.pageSize) to terminalFetch
        }

        private data class Record(val key: String)

        private class OffsetServer(
            private val items: List<String>,
            private val params: ExhaustFixtureParams,
        ) {
            private var pageCalls = 0
            var fetchCount: Int = 0

            fun fetch(limit: Int, offset: Int): List<String> {
                fetchCount++
                pageCalls++
                if (pageCalls > params.faultAfterPage) {
                    return items.take(limit)
                }
                return items.drop(offset).take(limit)
            }
        }

        private class CursorServer(
            private val items: List<Record>,
            private val params: ExhaustFixtureParams,
        ) {
            private var pageCalls = 0
            private var stickyKey: String? = null
            var fetchCount: Int = 0

            fun fetch(limit: Int, after: String?): List<Record> {
                fetchCount++
                pageCalls++
                if (pageCalls > params.faultAfterPage) {
                    val anchor = stickyKey ?: items.firstOrNull()?.key
                    stickyKey = anchor
                    val start = anchor?.let { key -> items.indexOfFirst { it.key == key } } ?: 0
                    return items.drop(start.coerceAtLeast(0)).take(limit)
                }
                val start = after?.let { key -> items.indexOfFirst { it.key == key } + 1 } ?: 0
                val page = items.drop(start.coerceAtLeast(0)).take(limit)
                if (pageCalls == params.faultAfterPage) stickyKey = page.firstOrNull()?.key
                return page
            }
        }
    }
}
