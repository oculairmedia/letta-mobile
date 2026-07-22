package com.letta.mobile.appservercli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HttpReadinessProbeTest {
    @Test
    fun `derives http readyz uri from ws listen url`() {
        assertEquals(
            "http://127.0.0.1:4500/readyz",
            HttpReadinessProbe.toHttpReadyUri("ws://127.0.0.1:4500", "/readyz").toString(),
        )
    }

    @Test
    fun `maps wss to https`() {
        assertEquals(
            "https://example.com:8443/readyz",
            HttpReadinessProbe.toHttpReadyUri("wss://example.com:8443", "/readyz").toString(),
        )
    }

    @Test
    fun `defaults port when absent`() {
        assertEquals(
            "http://localhost:4500/healthz",
            HttpReadinessProbe.toHttpReadyUri("ws://localhost", "/healthz").toString(),
        )
    }
}
