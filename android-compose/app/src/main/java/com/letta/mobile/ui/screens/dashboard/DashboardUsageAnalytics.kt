package com.letta.mobile.ui.screens.dashboard

import com.letta.mobile.data.model.Step
import kotlin.math.roundToInt

@androidx.compose.runtime.Immutable
data class ModelTokenUsage(
    val model: String,
    val totalTokens: Int,
    val sharePercent: Int,
)

@androidx.compose.runtime.Immutable
data class DashboardUsageSummary(
    val totalTokens: Int,
    val averageTokensPerHour: Int,
    val sampledSteps: Int,
    val modelUsage: List<ModelTokenUsage>,
)

internal object DashboardUsageCalculator {
    private const val FALLBACK_MODEL_NAME = "Unknown model"

    fun calculate(steps: List<Step>, windowHours: Int = 24): DashboardUsageSummary {
        val normalizedSteps = steps.mapNotNull { step ->
            val totalTokens = step.totalTokens ?: ((step.promptTokens ?: 0) + (step.completionTokens ?: 0))
            if (totalTokens <= 0) {
                null
            } else {
                step to totalTokens
            }
        }

        val totalTokens = normalizedSteps.sumOf { it.second }
        val averageTokensPerHour = if (windowHours > 0) {
            (totalTokens.toDouble() / windowHours.toDouble()).roundToInt()
        } else {
            0
        }

        val modelUsage = if (totalTokens > 0) {
            normalizedSteps
                .groupBy(
                    keySelector = { (step, _) -> step.model?.takeIf { it.isNotBlank() } ?: FALLBACK_MODEL_NAME },
                    valueTransform = { (_, tokens) -> tokens },
                )
                .map { (model, tokens) ->
                    val modelTotal = tokens.sum()
                    ModelTokenUsage(
                        model = model,
                        totalTokens = modelTotal,
                        sharePercent = ((modelTotal.toDouble() / totalTokens.toDouble()) * 100.0).roundToInt(),
                    )
                }
                .sortedWith(compareByDescending<ModelTokenUsage> { it.totalTokens }.thenBy { it.model })
        } else {
            emptyList()
        }

        return DashboardUsageSummary(
            totalTokens = totalTokens,
            averageTokensPerHour = averageTokensPerHour,
            sampledSteps = normalizedSteps.size,
            modelUsage = modelUsage,
        )
    }
}
