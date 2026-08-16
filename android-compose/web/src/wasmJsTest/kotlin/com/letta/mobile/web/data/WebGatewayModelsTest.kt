package com.letta.mobile.web.data

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.web.encodeWebImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    @Test
    fun `request ids do not collide after a page reload`() {
        val firstPage = webRequestId("page-a", "message", 9)
        val reloadedPage = webRequestId("page-b", "message", 9)

        assertNotEquals(firstPage, reloadedPage)
        assertEquals("web-page-b-message-9", reloadedPage)
    }

    @Test
    fun `fanout user echo preserves image attachments`() {
        val delta = Json.parseToJsonElement(
            """{"message_type":"user_message","id":"user-1","content":[{"type":"text","text":"look"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}]}""",
        )
        val update = decodeWebConversationUpdate(streamFrame(delta)) as WebConversationUpdate.Upsert
        assertEquals("look", update.entry.text)
        assertEquals("image/png", update.entry.attachments.single().mediaType)
    }

    @Test
    fun `fanout assistant snapshot becomes a live upsert`() {
        val delta = Json.parseToJsonElement(
            """{"message_type":"assistant_message","id":"assistant-1","content":"hello"}""",
        )
        val update = decodeWebConversationUpdate(streamFrame(delta)) as WebConversationUpdate.Upsert
        assertEquals("assistant-1", update.entry.id)
        assertEquals("hello", update.entry.text)
        assertEquals(false, update.entry.isUser)
    }

    @Test
    fun `browser image encoding enforces type and byte cap`() {
        val image = encodeWebImage("photo.png", "hi".encodeToByteArray())
        assertEquals("image/png", image.mediaType)
        assertEquals("aGk=", image.base64)
        assertFailsWith<IllegalArgumentException> { encodeWebImage("photo.txt", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> {
            encodeWebImage(
                "photo.png",
                byteArrayOf(1, 2),
                AttachmentLimits(maxRawBytesPerImage = 1),
            )
        }
    }

    private fun streamFrame(delta: kotlinx.serialization.json.JsonElement): AppServerReceivedFrame {
        val runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-1")
        return AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = 1,
                emittedAt = "2026-08-15T00:00:00Z",
                idempotencyKey = "frame-1",
                delta = delta,
            ),
            raw = JsonObject(emptyMap()),
        )
    }
}
