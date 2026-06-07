package com.letta.mobile.data.stream

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SseParserCommonTest {
    @Test
    fun parseFramesHandlesLivenessFramesAndInvalidJson() = runTest {
        val sse = """: ping

data: not-json

data: {"delta":{"tool":"partial"}}

data: {"id":"bad","message_type":"assistant_message",

data: {"id":"u1","message_type":"user_message","content":"Hi"}

"""

        val frames = SseParser.parseFrames(channelFrom(sse)).toList()

        assertEquals(4, frames.size)
        assertEquals(SseFrame.Heartbeat, frames[0])
        assertEquals(SseFrame.Heartbeat, frames[1])
        assertEquals(SseFrame.Heartbeat, frames[2])
        assertIs<SseFrame.Message>(frames[3])
        assertIs<UserMessage>((frames[3] as SseFrame.Message).message)
    }

    @Test
    fun doneSentinelTerminatesWithoutEmittingLaterMessages() = runTest {
        val sse = """data: {"id":"u1","message_type":"user_message","content":"Hi"}

data: [DONE]

data: {"id":"a1","message_type":"assistant_message","content":"late"}

"""

        val messages = SseParser.parse(channelFrom(sse)).toList()

        assertEquals(1, messages.size)
        assertIs<UserMessage>(messages.single())
    }

    @Test
    fun multiLineDataEventDecodesMessage() = runTest {
        val sse = """data: {"id":"a1",
data: "message_type":"assistant_message","content":"Hello"}

"""

        val messages = SseParser.parse(channelFrom(sse)).toList()

        assertEquals("Hello", assertIs<AssistantMessage>(messages.single()).content)
    }

    @Test
    fun chunkedUtf8LineBoundariesDecodeMessages() = runTest {
        val sse = """data: {"id":"a1","message_type":"assistant_message","content":"Hi"}

data: {"id":"tc1","message_type":"tool_call_message","tool_call":{"name":"search","arguments":"{}","type":"function"}}

data: {"id":"tr1","message_type":"tool_return_message","tool_return":"ok","status":"success","tool_call_id":"call-1"}

"""

        val messages = SseParser.parse(chunkedChannelFrom(sse, chunkSize = 3)).toList()

        assertEquals(3, messages.size)
        assertIs<AssistantMessage>(messages[0])
        assertEquals("search", assertIs<ToolCallMessage>(messages[1]).toolCall?.name)
        assertEquals("ok", assertIs<ToolReturnMessage>(messages[2]).toolReturn.funcResponse)
    }

    @Test
    fun parseRawEventsPreservesEventDataAndId() = runTest {
        val sse = """event: step
id: evt-1
data: {"type":"step"}

data: [DONE]

"""

        val events = SseParser.parseRawEvents(channelFrom(sse)).toList()

        assertEquals(1, events.size)
        assertEquals("step", events.single().event)
        assertEquals("evt-1", events.single().id)
        assertEquals("""{"type":"step"}""", events.single().data)
    }

    private fun channelFrom(text: String): ByteReadChannel =
        ByteReadChannel(text.encodeToByteArray())

    private fun TestScope.chunkedChannelFrom(text: String, chunkSize: Int): ByteReadChannel {
        val channel = ByteChannel(autoFlush = true)
        launch {
            text.chunked(chunkSize).forEach { chunk ->
                channel.writeStringUtf8(chunk)
            }
            channel.close()
        }
        return channel
    }
}
