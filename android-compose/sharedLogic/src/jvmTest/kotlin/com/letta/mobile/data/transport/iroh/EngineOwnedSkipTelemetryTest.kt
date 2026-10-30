package com.letta.mobile.data.transport.iroh

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EngineOwnedSkipTelemetryTest {

    @Test
    fun `init throws when flushEvery is 0`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            EngineOwnedSkipTelemetry(flushEvery = 0) {}
        }
        assertEquals("flushEvery must be positive", exception.message)
    }

    @Test
    fun `init throws when flushEvery is -1`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            EngineOwnedSkipTelemetry(flushEvery = -1) {}
        }
        assertEquals("flushEvery must be positive", exception.message)
    }

    @Test
    fun `init accepted when flushEvery is 1`() {
        // Should not throw
        EngineOwnedSkipTelemetry(flushEvery = 1) {}
    }
}
