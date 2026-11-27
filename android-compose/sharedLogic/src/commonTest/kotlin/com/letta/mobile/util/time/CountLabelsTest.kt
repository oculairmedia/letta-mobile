package com.letta.mobile.util.time

import kotlin.test.Test
import kotlin.test.assertEquals

/** The compact count label the subagent roster's subtitle and the settings pages share. */
class CountLabelsTest {
    @Test
    fun smallCountsStayExactAndUnitsStayCompact() {
        assertEquals("980", CountLabels.compact(980))
        assertEquals("9949", CountLabels.compact(9_949))
        assertEquals("10.0k", CountLabels.compact(9_950))
        assertEquals("12.3k", CountLabels.compact(12_345))
        assertEquals("1.0M", CountLabels.compact(999_950))
        assertEquals("1.2M", CountLabels.compact(1_234_567))
    }
}
