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
    fun memoryUpkeepIsBackground() {
        // letta-mobile-iczm7: letta-code's git-conflict / invalid-memory repair workers.
        listOf("Memory", "memory", " MEMORY ").forEach { label ->
            assertEquals(SubagentKind.Memory, SubagentKind.fromWire(label), label)
            assertTrue(SubagentKind.fromWire(label).isBackground, label)
        }
        assertFalse(SubagentKind.fromWire("Memory palace").isBackground)
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
}
