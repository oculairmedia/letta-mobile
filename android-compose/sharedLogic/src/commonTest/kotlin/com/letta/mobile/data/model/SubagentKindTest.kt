package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubagentKindTest {
    @Test
    fun hostReflectionLabelIsBackground() {
        assertEquals(SubagentKind.Reflection, SubagentKind.fromWire("Reflection"))
        assertTrue(SubagentKind.fromWire("Reflection").isBackground)
    }

    @Test
    fun hostReflectionIntegrationLabelIsBackground() {
        assertEquals(SubagentKind.Reflection, SubagentKind.fromWire("Reflection integration"))
        assertTrue(SubagentKind.fromWire("Reflection integration").isBackground)
    }

    @Test
    fun lowercaseReflectionIsBackground() {
        assertEquals(SubagentKind.Reflection, SubagentKind.fromWire("reflection"))
    }

    @Test
    fun generalPurposeIsVisible() {
        assertEquals(SubagentKind.GeneralPurpose, SubagentKind.fromWire("General-purpose"))
        assertFalse(SubagentKind.fromWire("General-purpose").isBackground)
    }

    @Test
    fun forkIsVisible() {
        assertEquals(SubagentKind.Fork, SubagentKind.fromWire("Fork"))
        assertFalse(SubagentKind.fromWire("Fork").isBackground)
    }

    @Test
    fun recallIsVisible() {
        assertEquals(SubagentKind.Recall, SubagentKind.fromWire("Recall"))
        assertFalse(SubagentKind.fromWire("Recall").isBackground)
    }

    @Test
    fun nullTypeIsOtherAndVisible() {
        assertEquals(SubagentKind.Other, SubagentKind.fromWire(null))
        assertFalse(SubagentKind.fromWire(null).isBackground)
    }

    @Test
    fun emptyTypeIsOtherAndVisible() {
        assertEquals(SubagentKind.Other, SubagentKind.fromWire(""))
        assertFalse(SubagentKind.fromWire("").isBackground)
    }

    @Test
    fun paddedMixedCaseLabelIsTrimmed() {
        assertEquals(SubagentKind.Reflection, SubagentKind.fromWire("  REFLECTION  "))
    }

    /**
     * letta-mobile-ulcg6: letta-code (0.33.6 and 0.34.8) runs memory git-conflict
     * and invalid-memory repair as subagentType "memory" (label "Memory"). It is
     * background upkeep, not user work, exactly like reflection.
     */
    @Test
    fun memoryRepairAndDreamRunsAreBackground() {
        assertEquals(SubagentKind.Memory, SubagentKind.fromWire("Memory"))
        assertTrue(SubagentKind.fromWire("Memory").isBackground)
        assertEquals(SubagentKind.Dream, SubagentKind.fromWire("Dream"))
        assertTrue(SubagentKind.fromWire("dreaming").isBackground)
        assertFalse(SubagentKind.fromWire("Claude-code").isBackground)
        assertFalse(SubagentKind.fromWire("Codex").isBackground)
    }
}
