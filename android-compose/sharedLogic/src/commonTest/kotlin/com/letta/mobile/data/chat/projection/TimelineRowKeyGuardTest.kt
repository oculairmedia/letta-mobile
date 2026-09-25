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
}
