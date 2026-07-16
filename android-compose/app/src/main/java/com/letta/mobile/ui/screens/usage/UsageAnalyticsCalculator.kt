package com.letta.mobile.ui.screens.usage

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Step
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

enum class TimeRange(val label: String, val hours: Int) {
    ONE_HOUR("1h", 1),
    TWENTY_FOUR_HOURS("24h", 24),
    SEVEN_DAYS("7d", 168),
    THIRTY_DAYS("30d", 720),
}

@androidx.compose.runtime.Immutable
data class UsageAnalytics(
    val totalTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int,
    val reasoningTokens: Int,
    val totalRuns: Int,
    val averageLatencyMs: Long,
    val errorCount: Int,
    val modelBreakdowns: List<ModelUsageBreakdown>,
    val agentBreakdowns: List<AgentUsageBreakdown>,
    val timeBuckets: List<TimeBucket>,
)

@androidx.compose.runtime.Immutable
data class ModelUsageBreakdown(
    val model: String,
    val totalTokens: Int,
    val sharePercent: Int,
)

@androidx.compose.runtime.Immutable
data class AgentUsageBreakdown(
    val agentId: String,
    val agentName: String,
    val totalTokens: Int,
    val sharePercent: Int,
)

@androidx.compose.runtime.Immutable
data class TimeBucket(
    val label: String,
    val totalTokens: Int,
)

internal object UsageAnalyticsCalculator {

    private const val FALLBACK_MODEL = "Unknown model"
    private const val FALLBACK_AGENT = "Unknown agent"
    private const val MAX_BUCKETS = 12

    fun calculate(
        steps: List<Step>,
        runs: List<Run>,
        agentNames: Map<String, String>,
        timeRange: TimeRange,
    ): UsageAnalytics {
        val totalTokens = steps.sumOf { resolveTokens(it) }
        val promptTokens = steps.sumOf { it.promptTokens ?: 0 }
        val completionTokens = steps.sumOf { it.completionTokens ?: 0 }
        val cachedTokens = steps.sumOf { extractCachedTokens(it) }
        val reasoningTokens = steps.sumOf { extractReasoningTokens(it) }
        val errorCount = steps.count { it.errorType != null }

        val avgLatencyMs = if (runs.isNotEmpty()) {
            val durationsMs = runs.mapNotNull { it.totalDurationNs?.let { ns -> ns / 1_000_000 } }
            if (durationsMs.isNotEmpty()) durationsMs.average().toLong() else 0L
        } else {
            0L
        }

        val modelBreakdowns = buildModelBreakdowns(steps, totalTokens)
        val agentBreakdowns = buildAgentBreakdowns(steps, agentNames, totalTokens)
        val timeBuckets = buildTimeBuckets(steps, timeRange)

        return UsageAnalytics(
            totalTokens = totalTokens,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            reasoningTokens = reasoningTokens,
            totalRuns = runs.size,
            averageLatencyMs = avgLatencyMs,
            errorCount = errorCount,
            modelBreakdowns = modelBreakdowns,
            agentBreakdowns = agentBreakdowns,
            timeBuckets = timeBuckets,
        )
    }

    private fun resolveTokens(step: Step): Int {
        return step.totalTokens ?: ((step.promptTokens ?: 0) + (step.completionTokens ?: 0))
    }

    private fun extractCachedTokens(step: Step): Int {
        return step.completionTokensDetails["cached_tokens"]
            ?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            ?: 0
    }

    private fun extractReasoningTokens(step: Step): Int {
        return step.completionTokensDetails["reasoning_tokens"]
            ?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            ?: 0
    }

    private fun buildModelBreakdowns(steps: List<Step>, totalTokens: Int): List<ModelUsageBreakdown> {
        if (totalTokens == 0) return emptyList()
        return steps
            .groupBy { it.model?.takeIf(String::isNotBlank) ?: FALLBACK_MODEL }
            .map { (model, modelSteps) ->
                val modelTotal = modelSteps.sumOf { resolveTokens(it) }
                ModelUsageBreakdown(
                    model = model,
                    totalTokens = modelTotal,
                    sharePercent = ((modelTotal.toDouble() / totalTokens) * 100.0).roundToInt(),
                )
            }
            .sortedByDescending { it.totalTokens }
    }

    private fun buildAgentBreakdowns(
        steps: List<Step>,
        agentNames: Map<String, String>,
        totalTokens: Int,
    ): List<AgentUsageBreakdown> {
        if (totalTokens == 0) return emptyList()
        return steps
            .groupBy { it.agentId ?: "unknown" }
            .map { (agentId, agentSteps) ->
                val agentTotal = agentSteps.sumOf { resolveTokens(it) }
                AgentUsageBreakdown(
                    agentId = agentId,
                    agentName = agentNames[agentId] ?: FALLBACK_AGENT,
                    totalTokens = agentTotal,
                    sharePercent = ((agentTotal.toDouble() / totalTokens) * 100.0).roundToInt(),
                )
            }
            .sortedByDescending { it.totalTokens }
    }

    private fun buildTimeBuckets(steps: List<Step>, timeRange: TimeRange): List<TimeBucket> {
        val now = Instant.now()
        val windowStart = now.minus(timeRange.hours.toLong(), ChronoUnit.HOURS)

        val bucketCount = when (timeRange) {
            TimeRange.ONE_HOUR -> 6
            TimeRange.TWENTY_FOUR_HOURS -> 12
            TimeRange.SEVEN_DAYS -> 7
            TimeRange.THIRTY_DAYS -> 10
        }
        val bucketDurationMinutes = (timeRange.hours * 60L) / bucketCount

        val buckets = (0 until bucketCount).map { i ->
            val bucketStart = windowStart.plus(i * bucketDurationMinutes, ChronoUnit.MINUTES)
            val bucketEnd = windowStart.plus((i + 1) * bucketDurationMinutes, ChronoUnit.MINUTES)

            val label = when (timeRange) {
                TimeRange.ONE_HOUR -> "${i * 10}m"
                TimeRange.TWENTY_FOUR_HOURS -> "${i * 2}h"
                TimeRange.SEVEN_DAYS -> "D${i + 1}"
                TimeRange.THIRTY_DAYS -> "D${i * 3 + 1}"
            }

            TimeBucket(label = label, totalTokens = 0)
        }

        if (steps.isEmpty()) return buckets

        val perBucket = steps.size / bucketCount.coerceAtLeast(1)
        val remainder = steps.size % bucketCount.coerceAtLeast(1)
        val sortedSteps = steps.toList()
        var offset = 0
        return buckets.mapIndexed { index, bucket ->
            val count = perBucket + if (index < remainder) 1 else 0
            val bucketSteps = sortedSteps.subList(
                offset.coerceAtMost(sortedSteps.size),
                (offset + count).coerceAtMost(sortedSteps.size),
            )
            offset += count
            bucket.copy(totalTokens = bucketSteps.sumOf { resolveTokens(it) })
        }
    }
}
