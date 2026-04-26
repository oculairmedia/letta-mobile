package com.letta.mobile.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("integration")
class LettaMessageSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixture(name: String): String {
        return javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()
    }

    @Test
    fun `deserialize user_message`() {
        val raw = loadFixture("user_message.json")
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is UserMessage)
        assertEquals("msg-001", msg.id)
        assertEquals("user_message", msg.messageType)
        assertEquals("Hello, how are you?", (msg as UserMessage).content)
    }

    @Test
    fun `deserialize assistant_message`() {
        val raw = loadFixture("assistant_message.json")
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is AssistantMessage)
        assertEquals("msg-002", msg.id)
        assertEquals("I'm doing great! How can I help you?", (msg as AssistantMessage).content)
    }

    @Test
    fun `deserialize reasoning_message`() {
        val raw = loadFixture("reasoning_message.json")
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is ReasoningMessage)
        assertEquals("msg-003", msg.id)
        assertEquals("Let me think about this step by step...", (msg as ReasoningMessage).reasoning)
    }

    @Test
    fun `deserialize tool_call_message`() {
        val raw = loadFixture("tool_call_message.json")
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is ToolCallMessage)
        val tcm = msg as ToolCallMessage
        val toolCall = tcm.toolCall
        assertEquals("msg-004", tcm.id)
        assertNotNull(toolCall)
        assertEquals("web_search", toolCall?.name)
        assertEquals("tc-001", toolCall?.effectiveId)
        assertTrue(toolCall?.arguments?.contains("Kotlin") == true)
    }

    @Test
    fun `deserialize tool_return_message`() {
        val raw = loadFixture("tool_return_message.json")
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is ToolReturnMessage)
        val trm = msg as ToolReturnMessage
        assertEquals("msg-005", trm.id)
        assertEquals("tc-001", trm.toolReturn.toolCallId)
        assertEquals("success", trm.toolReturn.status)
        assertNotNull(trm.toolReturn.funcResponse)
    }

    @Test
    fun `deserialize unknown message_type falls back to UnknownMessage`() {
        val raw = """{"id":"msg-999","message_type":"future_new_type","date":"2024-01-01T00:00:00Z"}"""
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is UnknownMessage)
        assertEquals("msg-999", msg.id)
    }

    @Test
    fun `UserMessage extracts content from string`() {
        val raw = """{"id":"1","message_type":"user_message","content":"plain text"}"""
        val msg = json.decodeFromString<LettaMessage>(raw) as UserMessage
        assertEquals("plain text", msg.content)
    }

    @Test
    fun `UserMessage extracts content from array`() {
        val raw = loadFixture("content_array_message.json")
        val msg = json.decodeFromString<LettaMessage>(raw) as UserMessage
        assertEquals("First part\nSecond part", msg.content)
    }

    @Test
    fun `UserMessage handles null content`() {
        val raw = """{"id":"1","message_type":"user_message"}"""
        val msg = json.decodeFromString<LettaMessage>(raw) as UserMessage
        assertEquals("", msg.content)
    }

    @Test
    fun `ToolReturnMessage handles string tool_return`() {
        val raw = """{"id":"1","message_type":"tool_return_message","tool_return":"simple result"}"""
        val msg = json.decodeFromString<LettaMessage>(raw) as ToolReturnMessage
        assertEquals("simple result", msg.toolReturn.funcResponse)
    }

    @Test
    fun `deserialize hidden_reasoning_message`() {
        val raw = """{"id":"1","message_type":"hidden_reasoning_message","state":"redacted","hidden_reasoning":"secret"}"""
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is HiddenReasoningMessage)
        assertEquals("redacted", (msg as HiddenReasoningMessage).state)
    }

    @Test
    fun `deserialize event_message`() {
        val raw = """{"id":"1","message_type":"event_message","event_type":"heartbeat"}"""
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is EventMessage)
        assertEquals("heartbeat", (msg as EventMessage).eventType)
    }

    @Test
    fun `deserialize unknown message_type without id field uses synthetic UUID`() {
        val raw = """{"message_type":"completely_unknown","date":"2024-01-01T00:00:00Z"}"""
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is UnknownMessage)
        assertNotNull(msg.id)
        assertTrue(msg.id.startsWith("unknown-"))
    }

    @Test
    fun `deserialize stop_reason`() {
        val raw = """{"stop_reason":"max_tokens","message_type":"stop_reason"}"""
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is StopReason)
        assertEquals("max_tokens", (msg as StopReason).reason)
        assertNotNull(msg.id)
    }

    @Test
    fun `deserialize error_message picks up content`() {
        // letta-mobile-5s1n: server emits error_message frames when a run
        // aborts mid-flight; previously fell through to UnknownMessage and
        // was silently dropped, leaving the user with a vanished spinner.
        val raw = """{"id":"err-1","message_type":"error_message","content":"tool exec failed"}"""
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is ErrorMessage)
        assertEquals("tool exec failed", (msg as ErrorMessage).text)
        assertEquals("err-1", msg.id)
    }

    @Test
    fun `deserialize error short form falls back to message field`() {
        // SSE sometimes uses the short discriminator "error" and puts the
        // human-readable string under `message` rather than `content`.
        val raw = """{"id":"err-2","message_type":"error","message":"rate limited"}"""
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is ErrorMessage)
        assertEquals("rate limited", (msg as ErrorMessage).text)
    }

    @Test
    fun `deserialize error_message without id synthesizes one`() {
        val raw = """{"message_type":"error_message","content":"oops"}"""
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is ErrorMessage)
        assertNotNull(msg.id)
        assertTrue(msg.id.startsWith("error-"))
    }

    @Test
    fun `deserialize usage_statistics`() {
        val raw = """{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,"message_type":"usage_statistics"}"""
        val msg = json.decodeFromString<LettaMessage>(raw)
        assertTrue(msg is UsageStatistics)
        val usage = msg as UsageStatistics
        assertEquals(100, usage.promptTokens)
        assertEquals(50, usage.completionTokens)
        assertEquals(150, usage.totalTokens)
        assertNotNull(msg.id)
    }
}
