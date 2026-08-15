package com.letta.mobile.web.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

class WebGatewayModelsTest {
    @Test
    fun `http endpoints resolve to the one socket app server path`() {
        assertEquals("ws://localhost:4500/ws", resolveWebSocketUrl("http://localhost:4500/"))
        assertEquals("wss://api.example.com/ws", resolveWebSocketUrl("https://api.example.com"))
        assertEquals("ws://localhost:4500/ws", resolveWebSocketUrl("ws://localhost:4500/ws"))
    }

    @Test
    fun `iroh endpoint never falls back to websocket`() {
        assertFailsWith<IllegalStateException> {
            resolveWebSocketUrl("iroh://0123456789abcdef")
        }
    }

    @Test
    fun `agent list uses only server returned rows`() {
        val rows = Json.parseToJsonElement(
            """[{"id":"agent-1","name":"Nora","model":"openai/gpt-5"}]""",
        ).jsonArray
        val agents = decodeWebAgents(rows)
        assertEquals(1, agents.size)
        assertEquals("agent-1", agents.single().id)
        assertEquals("Nora", agents.single().name)
    }

    @Test
    fun `assistant text merge accepts cumulative and incremental frames`() {
        assertEquals("hello", mergeAssistantText("hel", "hello"))
        assertEquals("hello world", mergeAssistantText("hello", " world"))
        assertEquals("hello", mergeAssistantText("hello", "hello"))
    }
}
