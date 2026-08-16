package com.letta.mobile.data.timeline

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineRuntimeWasmTest {
    @Test
    fun `browser transport failures are retryable`() {
        assertTrue(isTimelineNetworkFailure(IllegalStateException("Connection closed")))
        assertTrue(isTimelineNetworkFailure(IllegalStateException("Request timed out")))
        assertFalse(isTimelineNetworkFailure(IllegalArgumentException("Invalid payload")))
    }
}
