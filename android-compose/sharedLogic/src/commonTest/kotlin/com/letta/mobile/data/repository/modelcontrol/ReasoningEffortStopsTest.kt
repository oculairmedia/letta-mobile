package com.letta.mobile.data.repository.modelcontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-3io8k: the effort slider's stops come from the model's own tiers. */
class ReasoningEffortStopsTest {
    @Test
    fun defaultLeadsTheModelsTiers() {
        val stops = ReasoningEffortStops.of(listOf("low", "medium", "high"))!!
        assertEquals(listOf(null, "low", "medium", "high"), stops.values)
    }

    @Test
    fun noTiersOrASingleTierHasNoSlider() {
        assertNull(ReasoningEffortStops.of(emptyList()))
        assertNull(ReasoningEffortStops.of(listOf("high")))
        assertNull(ReasoningEffortStops.of(listOf("high", " HIGH ", "")))
    }

    @Test
    fun theCurrentEffortFindsItsStop() {
        val stops = ReasoningEffortStops.of(listOf("none", "minimal", "low", "medium", "high"))!!
        assertEquals(5, stops.indexOf("high"))
        assertEquals(0, stops.indexOf(null))
        assertEquals(0, stops.indexOf("xhigh"))
        assertEquals("minimal", stops.valueAt(2))
        assertEquals("high", stops.valueAt(99))
    }
}
