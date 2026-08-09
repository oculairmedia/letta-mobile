package com.letta.mobile.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatHelpersTest {

    @Test
    fun testFormatCompactCount() {
        assertEquals("987", FormatHelpers.formatCompactCount(987))
        assertEquals("10.0k", FormatHelpers.formatCompactCount(9_950))
        assertEquals("12.3k", FormatHelpers.formatCompactCount(12_345))
        assertEquals("1.0M", FormatHelpers.formatCompactCount(999_950))
        assertEquals("1.2M", FormatHelpers.formatCompactCount(1_240_000))
    }

    @Test
    fun testFormatByteSize() {
        assertEquals("0 B", FormatHelpers.formatByteSize(0))
        assertEquals("500 B", FormatHelpers.formatByteSize(500))
        assertEquals("4.2 KB", FormatHelpers.formatByteSize(4300))
        assertEquals("1.0 MB", FormatHelpers.formatByteSize(1_048_575))
        assertEquals("1.5 MB", FormatHelpers.formatByteSize(1_572_864))
    }

    @Test
    fun testFormatDuration() {
        assertEquals("—", FormatHelpers.formatDurationValue(0))
        assertEquals("", FormatHelpers.formatDurationSuffix(0))
        assertEquals("850", FormatHelpers.formatDurationValue(850))
        assertEquals("ms", FormatHelpers.formatDurationSuffix(850))
        assertEquals("2.5", FormatHelpers.formatDurationValue(2_500))
        assertEquals("s", FormatHelpers.formatDurationSuffix(2_500))
    }
}
