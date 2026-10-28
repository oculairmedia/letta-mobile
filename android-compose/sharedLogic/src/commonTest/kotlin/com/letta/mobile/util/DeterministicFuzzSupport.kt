package com.letta.mobile.util

import kotlin.random.Random

/**
 * Shared seed corpus and failure context for seeded property tests in commonTest.
 * Automatic shrinking is limited to [minimizeBySuffixDeletion] (suffix deletion with a
 * validity precondition); there is no full delta-debugger.
 */
internal object FuzzCorpus {
    val paginationSeeds: List<Long> = listOf(
        0x91A80C2B_0001L,
        0x91A80C2B_0002L,
        0x91A80C2B_0003L,
        0x91A80C2B_0004L,
        0x91A80C2B_0005L,
        0x91A80C2B_0006L,
    )

    val timelineSeeds: List<Long> = listOf(
        0x91A80C2B_1001L,
        0x91A80C2B_1002L,
        0x91A80C2B_1003L,
        0x91A80C2B_1004L,
        0x91A80C2B_1005L,
        0x91A80C2B_1006L,
    )

    const val SCENARIOS_PER_SEED: Int = 50
}

/**
 * Per-scenario PRNG derived from the corpus seed and scenario index (portable; no JVM APIs).
 * Reproduce one scenario with `Random(scenarioSeed(baseSeed, scenarioIndex))`.
 */
internal fun scenarioSeed(baseSeed: Long, scenarioIndex: Int): Long =
    baseSeed xor (scenarioIndex.toLong() * 0x9E3779B9L)

internal fun scenarioRandom(baseSeed: Long, scenarioIndex: Int): Random =
    Random(scenarioSeed(baseSeed, scenarioIndex))

internal class FuzzCaseFailure(
    val seed: Long,
    val scenarioIndex: Int,
    val operationIndex: Int?,
    detail: String,
) : AssertionError(
    buildString {
        append("seed=$seed scenario=$scenarioIndex")
        if (operationIndex != null) append(" op=$operationIndex")
        append(": ")
        append(detail)
    },
)

internal inline fun checkFuzz(
    seed: Long,
    scenarioIndex: Int,
    operationIndex: Int? = null,
    condition: Boolean,
    lazyMessage: () -> String,
) {
    if (!condition) {
        throw FuzzCaseFailure(seed, scenarioIndex, operationIndex, lazyMessage())
    }
}

internal data class SuffixMinimizeResult<T>(
    val minimized: List<T>,
    val trace: String,
)

/**
 * Drops trailing operations while [isValid] stays true and [invokeScenario] keeps throwing
 * [FuzzCaseFailure]. Other throwables propagate (cancellation, OOM, invalid fixtures).
 */
internal fun <T> minimizeBySuffixDeletion(
    operations: List<T>,
    isValid: (List<T>) -> Boolean,
    invokeScenario: (List<T>) -> Unit,
    formatOp: (T) -> String = { it.toString() },
): SuffixMinimizeResult<T> {
    if (!failsFuzzInvariant(operations, invokeScenario)) {
        return SuffixMinimizeResult(operations, traceFor(operations, formatOp))
    }
    var best = operations
    var candidate = operations.dropLast(1)
    while (candidate.isNotEmpty() && isValid(candidate)) {
        if (!failsFuzzInvariant(candidate, invokeScenario)) break
        best = candidate
        candidate = candidate.dropLast(1)
    }
    return SuffixMinimizeResult(best, traceFor(best, formatOp))
}

private fun <T> failsFuzzInvariant(operations: List<T>, invokeScenario: (List<T>) -> Unit): Boolean =
    try {
        invokeScenario(operations)
        false
    } catch (_: FuzzCaseFailure) {
        true
    }

private fun <T> traceFor(operations: List<T>, formatOp: (T) -> String): String =
    operations.mapIndexed { index, op -> "$index:${formatOp(op)}" }.joinToString("|")

internal fun Random.pickAlphanumeric(length: Int): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString(length) { repeat(length) { append(chars[nextInt(chars.length)]) } }
}
