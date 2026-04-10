package com.letta.mobile.data.stream

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SseParserTest {

    private fun channelFrom(text: String): ByteReadChannel {
        return ByteReadChannel(text.toByteArray())
    }

    @Test
    fun `parse single user_message`() = runTest {
        val sse = "data: {\"id\":\"1\",\"message_type\":\"user_message\",\"content\":\"Hello\"}\n\n"
        val results = SseParser.parse(channelFrom(sse)).toList()
        assertEquals(1, results.size)
        assertTrue(results[0] is UserMessage)
        assertEquals("Hello", (results[0] as UserMessage).content)
    }

    @Test
    fun `parse single assistant_message`() = runTest {
        val sse = "data: {\"id\":\"2\",\"message_type\":\"assistant_message\",\"content\":\"Hi there\"}\n\n"
        val results = SseParser.parse(channelFrom(sse)).toList()
        assertEquals(1, results.size)
        assertTrue(results[0] is AssistantMessage)
    }

    @Test
    fun `parse multiple messages`() = runTest {
        val sse = """data: {"id":"1","message_type":"user_message","content":"Hi"}

data: {"id":"2","message_type":"assistant_message","content":"Hello"}

"""
        val results = SseParser.parse(channelFrom(sse)).toList()
        assertEquals(2, results.size)
        assertTrue(results[0] is UserMessage)
        assertTrue(results[1] is AssistantMessage)
    }

    @Test
    fun `DONE sentinel terminates stream`() = runTest {
        val sse = """data: {"id":"1","message_type":"user_message","content":"Hi"}

data: [DONE]

data: {"id":"2","message_type":"assistant_message","content":"Should not appear"}

"""
        val results = SseParser.parse(channelFrom(sse)).toList()
        assertEquals(1, results.size)
    }

    @Test
    fun `malformed JSON is skipped`() = runTest {
        val sse = """data: {"id":"1","message_type":"user_message","content":"Hi"}

data: {invalid json here}

data: {"id":"2","message_type":"assistant_message","content":"OK"}

"""
        val results = SseParser.parse(channelFrom(sse)).toList()
        assertEquals(2, results.size)
    }

    @Test
    fun `empty stream returns no messages`() = runTest {
        val results = SseParser.parse(channelFrom("")).toList()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `ping messages are filtered`() = runTest {
        val sse = "data: {\"id\":\"1\",\"message_type\":\"ping\"}\n\n"
        val results = SseParser.parse(channelFrom(sse)).toList()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `reasoning_message parsed correctly`() = runTest {
        val sse = "data: {\"id\":\"1\",\"message_type\":\"reasoning_message\",\"reasoning\":\"Thinking...\"}\n\n"
        val results = SseParser.parse(channelFrom(sse)).toList()
        assertEquals(1, results.size)
        assertTrue(results[0] is ReasoningMessage)
        assertEquals("Thinking...", (results[0] as ReasoningMessage).reasoning)
    }

    @Test
    fun `tool_call_message parsed correctly`() = runTest {
        val sse = "data: {\"id\":\"1\",\"message_type\":\"tool_call_message\",\"tool_call\":{\"name\":\"search\",\"arguments\":\"{}\",\"type\":\"function\"}}\n\n"
        val results = SseParser.parse(channelFrom(sse)).toList()
        assertEquals(1, results.size)
        assertTrue(results[0] is ToolCallMessage)
        assertEquals("search", (results[0] as ToolCallMessage).toolCall?.name)
    }
}
