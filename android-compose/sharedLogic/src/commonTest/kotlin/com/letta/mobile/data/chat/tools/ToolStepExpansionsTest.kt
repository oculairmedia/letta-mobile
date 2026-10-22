package com.letta.mobile.data.chat.tools

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-2ccpv: which steps the person opened, kept as flips from their default, bounded, across reloads. */
class ToolStepExpansionsTest {
    @Test
    fun aStepIsItsDefaultUntilFlippedAndCollapsedStepsKeepNothing() {
        val expansions = ToolStepExpansions()
        assertFalse(expansions.isExpanded("a", openByDefault = false))
        assertTrue(expansions.isExpanded("img", openByDefault = true))
        expansions.toggle("a")
        assertTrue(expansions.isExpanded("a", openByDefault = false))
        expansions.toggle("a")
        assertEquals(emptyList(), expansions.toggled.value)
    }

    @Test
    fun flipsSurviveAReloadAndClosingTheLastOneClearsTheStore() {
        val settings = InMemorySecureSettingsStore()
        ToolStepExpansions(settings).toggle("call-1")
        val reloaded = ToolStepExpansions(settings)
        assertTrue(reloaded.isExpanded("call-1", openByDefault = false))
        reloaded.toggle("call-1")
        assertNull(settings.getString(ToolStepExpansions.KEY))
    }

    @Test
    fun pastItsCapacityTheOldestFlipIsForgotten() {
        val expansions = ToolStepExpansions(capacity = 2)
        listOf("a", "b", "c").forEach(expansions::toggle)
        assertEquals(listOf("b", "c"), expansions.toggled.value)
    }

    @Test
    fun anUnreadableStoredValueReadsAsNothingFlipped() {
        val settings = InMemorySecureSettingsStore(mapOf(ToolStepExpansions.KEY to "{not json"))
        assertEquals(emptyList(), ToolStepExpansions(settings).toggled.value)
    }
}
