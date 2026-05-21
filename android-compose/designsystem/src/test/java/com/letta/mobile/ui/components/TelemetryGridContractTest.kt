package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class TelemetryGridContractTest {

    @Test
    fun `formats compact token counts`() {
        assertEquals("987", formatCompactCount(987))
        assertEquals("12.3k", formatCompactCount(12_345))
        assertEquals("1.2M", formatCompactCount(1_240_000))
    }

    @Test
    fun `formats duration value and suffix`() {
        assertEquals("850", formatDurationValue(850))
        assertEquals("ms", formatDurationSuffix(850))
        assertEquals("2.5", formatDurationValue(2_500))
        assertEquals("s", formatDurationSuffix(2_500))
        assertEquals("1.5", formatDurationValue(90_000))
        assertEquals("m", formatDurationSuffix(90_000))
    }

    @Test
    fun `computes completion token speed`() {
        assertEquals("25.0", formatSpeedValue(completionTokens = 50, durationMs = 2_000))
        assertEquals("—", formatSpeedValue(completionTokens = 50, durationMs = 0))
        assertEquals("—", formatSpeedValue(completionTokens = 0, durationMs = 2_000))
    }

    @Test
    fun `formats cost chip as dollars`() {
        assertEquals("$0.0045", formatCostUsd(0.0045))
        assertEquals("$0.045", formatCostUsd(0.045))
        assertEquals("$1.20", formatCostUsd(1.2))
    }
}
