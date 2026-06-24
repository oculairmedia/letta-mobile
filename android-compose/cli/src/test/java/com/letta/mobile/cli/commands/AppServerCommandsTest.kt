package com.letta.mobile.cli.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppServerCommandsTest {
    @Test
    fun `normalizeAppServerWsUrl maps http schemes to websocket schemes`() {
        assertEquals("ws://127.0.0.1:8283", normalizeAppServerWsUrl("http://127.0.0.1:8283/"))
        assertEquals("wss://example.test/app-server", normalizeAppServerWsUrl("https://example.test/app-server"))
        assertEquals("ws://127.0.0.1:8283", normalizeAppServerWsUrl("ws://127.0.0.1:8283"))
        assertEquals("ws://127.0.0.1:8283", normalizeAppServerWsUrl("127.0.0.1:8283"))
    }

    @Test
    fun `smoke plan redacts token and records client dependency`() {
        val plan = buildAppServerSmokePlan(
            rawUrl = "http://127.0.0.1:8283",
            hasToken = true,
            message = "hello",
            cwd = ".",
            timeoutMs = 5_000,
        ).render()

        assertTrue(plan.contains("url=ws://127.0.0.1:8283"))
        assertTrue(plan.contains("auth=bearer token"))
        assertTrue(plan.contains("runtime_start"))
        assertTrue(plan.contains("letta-mobile-ph9ws.8"))
        assertFalse(plan.contains("APP_SERVER_TOKEN"))
    }
}
