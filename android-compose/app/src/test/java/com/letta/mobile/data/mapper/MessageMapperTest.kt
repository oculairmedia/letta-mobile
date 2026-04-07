package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.testutil.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMapperTest {

    @Test
    fun `user AppMessage maps to UI Message with role user`() {
        val appMsg = TestData.appMessage(messageType = MessageType.USER, content = "Hello")
        val uiMsg = appMsg.toUiMessage()
        assertEquals("user", uiMsg.role)
        assertEquals("Hello", uiMsg.content)
        assertEquals(false, uiMsg.isReasoning)
        assertNull(uiMsg.toolCalls)
    }

    @Test
    fun `assistant AppMessage maps to role assistant`() {
        val appMsg = TestData.appMessage(messageType = MessageType.ASSISTANT, content = "Hi there")
        val uiMsg = appMsg.toUiMessage()
        assertEquals("assistant", uiMsg.role)
        assertEquals("Hi there", uiMsg.content)
    }

    @Test
    fun `reasoning AppMessage maps to assistant role with isReasoning true`() {
        val appMsg = TestData.appMessage(messageType = MessageType.REASONING, content = "Thinking...")
        val uiMsg = appMsg.toUiMessage()
        assertEquals("assistant", uiMsg.role)
        assertTrue(uiMsg.isReasoning)
    }

    @Test
    fun `tool_call AppMessage maps to tool role with ToolCall`() {
        val appMsg = TestData.appMessage(
            messageType = MessageType.TOOL_CALL,
            content = "{\"query\": \"test\"}",
            toolName = "web_search",
            toolCallId = "tc-1"
        )
        val uiMsg = appMsg.toUiMessage()
        assertEquals("tool", uiMsg.role)
        assertEquals(1, uiMsg.toolCalls?.size)
        assertEquals("web_search", uiMsg.toolCalls?.first()?.name)
    }

    @Test
    fun `tool_return AppMessage maps to tool role without ToolCall`() {
        val appMsg = TestData.appMessage(
            messageType = MessageType.TOOL_RETURN,
            content = "Search results...",
            toolCallId = "tc-1"
        )
        val uiMsg = appMsg.toUiMessage()
        assertEquals("tool", uiMsg.role)
        assertNull(uiMsg.toolCalls)
    }

    @Test
    fun `timestamp is preserved`() {
        val appMsg = TestData.appMessage(id = "m1")
        val uiMsg = appMsg.toUiMessage()
        assertTrue(uiMsg.timestamp.isNotBlank())
    }

    @Test
    fun `id is preserved`() {
        val appMsg = TestData.appMessage(id = "unique-id-123")
        val uiMsg = appMsg.toUiMessage()
        assertEquals("unique-id-123", uiMsg.id)
    }
}
