package com.letta.mobile.data.chat.projection

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineRowKeyGuardTest {
    @Test
    fun repeatedKeyIsDroppedAfterItsFirstRow() {
        val keys = listOf("msg-live", "segment-ui-msg-9173264", null, "segment-ui-msg-9173264", "segment-a")
        val duplicates = TimelineRowKeyGuard.duplicateRows(keys)
        assertEquals(mapOf(3 to "segment-ui-msg-9173264#duplicate-3"), duplicates)
        val effective = keys.mapIndexed { index, key -> duplicates[index] ?: key ?: "placeholder-$index" }
        assertEquals(effective.size, effective.toSet().size)
    }

    @Test
    fun distinctKeysAndPlaceholdersNeedNothing() {
        assertEquals(emptyMap(), TimelineRowKeyGuard.duplicateRows(listOf("a", null, null, "b")))
    }

    @Test
    fun futureOriginalKeyKeepsItsIdentityWhenItMatchesDuplicateFallback() {
        val keys = listOf("x", "x", "x#duplicate-1")
        val duplicates = TimelineRowKeyGuard.duplicateRows(keys)
        assertEquals(mapOf(1 to "x#duplicate-1-1"), duplicates)
        assertEquals("x#duplicate-1", keys[2])
        val effective = keys.mapIndexed { index, key -> duplicates[index] ?: key ?: "placeholder-$index" }
        assertEquals(3, effective.size)
        assertEquals(3, effective.toSet().size)
    }

    @Test
    fun chainedFallbackCollisionsUseDeterministicUnusedSuffixes() {
        val keys = listOf("x", "x", "x", "x#duplicate-1", "x#duplicate-1-1", "x#duplicate-2")
        val duplicates = TimelineRowKeyGuard.duplicateRows(keys)
        assertEquals(mapOf(1 to "x#duplicate-1-2", 2 to "x#duplicate-2-1"), duplicates)
        assertEquals("x#duplicate-1", keys[3])
        assertEquals("x#duplicate-1-1", keys[4])
        assertEquals("x#duplicate-2", keys[5])
        val effective = keys.mapIndexed { index, key -> duplicates[index] ?: key ?: "placeholder-$index" }
        assertEquals(6, effective.size)
        assertEquals(6, effective.toSet().size)
    }

    @Test
    fun repeatedCallsPreserveOrdinaryFallbackNames() {
        val keys = listOf("x", "x", "y", "y")
        val expected = mapOf(1 to "x#duplicate-1", 3 to "y#duplicate-3")
        val duplicates = TimelineRowKeyGuard.duplicateRows(keys)
        assertEquals(expected, duplicates)
        assertEquals(expected, TimelineRowKeyGuard.duplicateRows(keys))
        val effective = keys.mapIndexed { index, key -> duplicates[index] ?: key ?: "placeholder-$index" }
        assertEquals(4, effective.size)
        assertEquals(4, effective.toSet().size)
    }
}
