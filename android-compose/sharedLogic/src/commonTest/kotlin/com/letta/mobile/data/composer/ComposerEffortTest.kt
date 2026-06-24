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
        assertEquals(ComposerEffort.Low, ComposerEffort.Minimal.increase())
        assertEquals(ComposerEffort.Medium, ComposerEffort.Low.increase())
        assertEquals(ComposerEffort.High, ComposerEffort.Medium.increase())
        assertEquals(ComposerEffort.Max, ComposerEffort.High.increase())
        assertEquals(ComposerEffort.Max, ComposerEffort.Max.increase()) // Bounded at Max

        // Test decreasing
        assertEquals(ComposerEffort.High, ComposerEffort.Max.decrease())
        assertEquals(ComposerEffort.Medium, ComposerEffort.High.decrease())
        assertEquals(ComposerEffort.Low, ComposerEffort.Medium.decrease())
        assertEquals(ComposerEffort.Minimal, ComposerEffort.Low.decrease())
        assertEquals(ComposerEffort.Minimal, ComposerEffort.Minimal.decrease()) // Bounded at Minimal
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
