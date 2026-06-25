package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StreamStateTest {

    @Test
    fun testSendingState() {
        val state: StreamState = StreamState.Sending
        assertTrue(state is StreamState.Sending)
    }

    @Test
    fun testStreamingState() {
        val messages = listOf(
            AppMessage(
                id = "1",
                date = Instant.now(),
                messageType = MessageType.USER,
                content = "Hello"
            )
        )
        val state = StreamState.Streaming(messages)

        assertEquals(messages, state.messages)
        assertEquals(1, state.messages.size)
        assertEquals("Hello", state.messages[0].content)
    }

    @Test
    fun testToolExecutionState() {
        val toolName = "search_web"
        val state = StreamState.ToolExecution(toolName)

        assertEquals(toolName, state.toolName)
    }

    @Test
    fun testCompleteState() {
        val messages = listOf(
            AppMessage(
                id = "2",
                date = Instant.now(),
                messageType = MessageType.ASSISTANT,
                content = "Response"
            )
        )
        val state = StreamState.Complete(messages)

        assertEquals(messages, state.messages)
        assertEquals(1, state.messages.size)
        assertEquals("Response", state.messages[0].content)
    }

    @Test
    fun testErrorState() {
        val errorMessage = "Network timeout"
        val state = StreamState.Error(errorMessage)

        assertEquals(errorMessage, state.message)
    }

    @Test
    fun testExhaustiveWhen() {
        // This test will fail to compile if a new state is added to the sealed interface
        // without updating this test, ensuring all states are accounted for.
        val state: StreamState = StreamState.Sending

        val result = when (state) {
            is StreamState.Sending -> "Sending"
            is StreamState.Streaming -> "Streaming"
            is StreamState.ToolExecution -> "ToolExecution"
            is StreamState.Complete -> "Complete"
            is StreamState.Error -> "Error"
        }

        assertEquals("Sending", result)
    }
}
