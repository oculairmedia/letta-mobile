package com.letta.mobile.web.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

class WebGatewayModelsTest {
    @Test
    fun `http endpoints resolve to the one socket app server path`() {
        assertEquals("ws://localhost:4500/ws", resolveWebSocketUrl("http://localhost:4500/"))
        assertEquals("wss://api.example.com/ws", resolveWebSocketUrl("https://api.example.com"))
        assertEquals("ws://localhost:4500/ws", resolveWebSocketUrl("ws://localhost:4500/ws"))
        assertEquals("ws://localhost:4500/ws", resolveWebSocketUrl("ws://localhost:4500"))
    }

    @Test
    fun `iroh endpoint never falls back to websocket`() {
        assertFailsWith<IllegalStateException> {
            resolveWebSocketUrl("iroh://0123456789abcdef")
        }
    }

    @Test
    fun `iroh auth asks the server to split replies larger than one frame`() {
        assertEquals(
            listOf("frame_part"),
            authCapabilitiesFor("iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f"),
        )
    }

    @Test
    fun `websocket auth advertises no framing capabilities`() {
        assertNull(authCapabilitiesFor("http://localhost:4500"))
        assertNull(authCapabilitiesFor("wss://api.example.com/ws"))
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
    fun `request ids do not collide after a page reload`() {
        val firstPage = webRequestId("page-a", "message", 9)
        val reloadedPage = webRequestId("page-b", "message", 9)

        assertNotEquals(firstPage, reloadedPage)
        assertEquals("web-page-b-message-9", reloadedPage)
    }
}
