package com.letta.mobile.data.composer

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerEffortTest {

    @Test
    fun testComposerEffortTransitions() {
        // Test increasing
        assertEquals(ComposerEffort.Minimal, ComposerEffort.None.increase())
        assertEquals(ComposerEffort.Low, ComposerEffort.Minimal.increase())
        assertEquals(ComposerEffort.Medium, ComposerEffort.Low.increase())
        assertEquals(ComposerEffort.High, ComposerEffort.Medium.increase())
        assertEquals(ComposerEffort.XHigh, ComposerEffort.High.increase())
        assertEquals(ComposerEffort.Max, ComposerEffort.XHigh.increase())
        assertEquals(ComposerEffort.Max, ComposerEffort.Max.increase()) // Bounded at Max

        // Test decreasing
        assertEquals(ComposerEffort.XHigh, ComposerEffort.Max.decrease())
        assertEquals(ComposerEffort.High, ComposerEffort.XHigh.decrease())
        assertEquals(ComposerEffort.Medium, ComposerEffort.High.decrease())
        assertEquals(ComposerEffort.Low, ComposerEffort.Medium.decrease())
        assertEquals(ComposerEffort.Minimal, ComposerEffort.Low.decrease())
        assertEquals(ComposerEffort.None, ComposerEffort.Minimal.decrease())
        assertEquals(ComposerEffort.None, ComposerEffort.None.decrease()) // Bounded at None
    }

    @Test
    fun theLadderIsTheFullUpstreamReasoningEffortSet() {
        // letta-mobile-bzvro.18: UpdateModelPayload.reasoning_effort in letta-code's protocol_v2.ts.
        assertEquals(
            listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"),
            ComposerEffort.entries.map { it.wire },
        )
        assertEquals(ComposerEffort.XHigh, ComposerEffort.fromWire("XHIGH"))
        assertEquals(null, ComposerEffort.fromWire("turbo"))
    }

    @Test
    fun sortedOrdersKnownEffortsAndKeepsUnknownOnesLast() {
        assertEquals(
            listOf("none", "low", "high", "xhigh", "max", "turbo"),
            ComposerEffort.sorted(listOf("max", "turbo", "high", "none", "xhigh", "low", "high")),
        )
    }

    @Test
    fun testComposerEffortStateToggles() {
        val initialState = ComposerEffortState()

        // Default state
        assertTrue(initialState.thinking)
        assertEquals(ComposerEffort.Medium, initialState.effort)

        // Toggle thinking
        val noThinkingState = initialState.toggleThinking()
        assertFalse(noThinkingState.thinking)
        assertEquals(ComposerEffort.Medium, noThinkingState.effort)

        // Increase effort
        val increasedEffortState = initialState.increaseEffort()
        assertTrue(increasedEffortState.thinking)
        assertEquals(ComposerEffort.High, increasedEffortState.effort)

        // Decrease effort
        val decreasedEffortState = initialState.decreaseEffort()
        assertTrue(decreasedEffortState.thinking)
        assertEquals(ComposerEffort.Low, decreasedEffortState.effort)
    }

    @Test
    fun testComposerEffortSerialization() {
        val json = Json { encodeDefaults = true }

        // Test Enum Serialization
        val minimalJson = json.encodeToString(ComposerEffort.Minimal)
        assertEquals("\"Minimal\"", minimalJson)
        val deserializedEffort = json.decodeFromString<ComposerEffort>(minimalJson)
        assertEquals(ComposerEffort.Minimal, deserializedEffort)

        // Test State Serialization
        val state = ComposerEffortState(thinking = false, effort = ComposerEffort.High)
        val stateJson = json.encodeToString(state)
        val deserializedState = json.decodeFromString<ComposerEffortState>(stateJson)
        
        assertEquals(state, deserializedState)
        assertFalse(deserializedState.thinking)
        assertEquals(ComposerEffort.High, deserializedState.effort)
    }
}
