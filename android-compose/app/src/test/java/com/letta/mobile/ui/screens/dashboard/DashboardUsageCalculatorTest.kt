package com.letta.mobile.ui.screens.dashboard

import com.letta.mobile.data.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardUsageCalculatorTest {

    @Test
    fun `calculate aggregates total hourly and model usage`() {
        val summary = DashboardUsageCalculator.calculate(
            steps = listOf(
                sampleStep(id = "step-1", model = "gpt-4.1", totalTokens = 1200),
                sampleStep(id = "step-2", model = "gpt-4.1", totalTokens = 300),
                sampleStep(id = "step-3", model = "claude-3.7", totalTokens = 900),
            ),
            windowHours = 24,
        )

        assertEquals(2400, summary.totalTokens)
        assertEquals(100, summary.averageTokensPerHour)
        assertEquals(3, summary.sampledSteps)
        assertEquals(listOf("gpt-4.1", "claude-3.7"), summary.modelUsage.map { it.model })
        assertEquals(1500, summary.modelUsage[0].totalTokens)
        assertEquals(63, summary.modelUsage[0].sharePercent)
        assertEquals(900, summary.modelUsage[1].totalTokens)
        assertEquals(38, summary.modelUsage[1].sharePercent)
    }

    @Test
    fun `calculate falls back to prompt plus completion tokens and unknown model`() {
        val summary = DashboardUsageCalculator.calculate(
            steps = listOf(
                sampleStep(id = "step-1", model = null, totalTokens = null, promptTokens = 40, completionTokens = 60),
            ),
            windowHours = 10,
        )

        assertEquals(100, summary.totalTokens)
        assertEquals(10, summary.averageTokensPerHour)
        assertEquals("Unknown model", summary.modelUsage.single().model)
        assertEquals(100, summary.modelUsage.single().sharePercent)
    }

    @Test
    fun `calculate ignores zero-token steps`() {
        val summary = DashboardUsageCalculator.calculate(
            steps = listOf(
                sampleStep(id = "step-1", model = "gpt-4.1", totalTokens = 0),
                sampleStep(id = "step-2", model = "gpt-4.1", totalTokens = null, promptTokens = 0, completionTokens = 0),
            ),
        )

        assertEquals(0, summary.totalTokens)
        assertEquals(0, summary.averageTokensPerHour)
        assertTrue(summary.modelUsage.isEmpty())
        assertEquals(0, summary.sampledSteps)
    }

    private fun sampleStep(
        id: String,
        model: String?,
        totalTokens: Int?,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
    ) = Step(
        id = id,
        model = model,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
    )
}
