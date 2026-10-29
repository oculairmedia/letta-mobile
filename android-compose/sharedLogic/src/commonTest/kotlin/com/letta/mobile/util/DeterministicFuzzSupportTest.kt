package com.letta.mobile.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Proves [minimizeBySuffixDeletion] shrinks failing operation lists when suffixes are redundant. */
class DeterministicFuzzSupportTest {
    @Test
    fun suffixMinimizerDropsTrailingNoOpsWhileFailurePersists() {
        val ops = listOf("need", "need", "tail", "tail")
        val result = minimizeBySuffixDeletion(
            operations = ops,
            isValid = { it.size >= 2 },
            invokeScenario = { list ->
                if (list.contains("tail")) {
                    throw FuzzCaseFailure(0L, 0, null, "trailing tail still present")
                }
            },
        )
        assertEquals(listOf("need", "need", "tail"), result.minimized)
        assertTrue(result.minimized.size < ops.size)
        assertTrue(result.trace.contains("0:need"))
    }

    @Test
    fun suffixMinimizerLeavesListWhenScenarioPasses() {
        val ops = listOf("a", "b", "c")
        val result = minimizeBySuffixDeletion(
            operations = ops,
            isValid = { true },
            invokeScenario = { /* pass */ },
        )
        assertEquals(ops, result.minimized)
    }

    @Test
    fun suffixMinimizerKeepsSingletonFailure() {
        val ops = listOf("only")
        val result = minimizeBySuffixDeletion(
            operations = ops,
            isValid = { it.isNotEmpty() },
            invokeScenario = { throw FuzzCaseFailure(1L, 0, null, "singleton") },
        )
        assertEquals(ops, result.minimized)
    }

    @Test
    fun suffixMinimizerStopsAtInvalidCandidate() {
        val ops = listOf("keep", "keep", "tail")
        val result = minimizeBySuffixDeletion(
            operations = ops,
            isValid = { it.size >= 2 },
            invokeScenario = { throw FuzzCaseFailure(1L, 0, null, "always") },
        )
        assertEquals(listOf("keep", "keep"), result.minimized)
    }

    @Test
    fun suffixMinimizerPropagatesNonFuzzFailures() {
        val ops = listOf("x")
        assertFailsWith<IllegalStateException> {
            minimizeBySuffixDeletion(
                operations = ops,
                isValid = { true },
                invokeScenario = { throw IllegalStateException("not a fuzz failure") },
            )
        }
    }

    @Test
    fun scenarioSeedIsDeterministicPerBaseSeedAndIndex() {
        assertEquals(
            scenarioSeed(0x91A80C2B_0001L, 3),
            scenarioSeed(0x91A80C2B_0001L, 3),
        )
        assertTrue(scenarioSeed(0x91A80C2B_0001L, 2) != scenarioSeed(0x91A80C2B_0001L, 3))
    }
}
