package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageContentFactoryTest {

    @Test
    fun `tool call entrance animation is limited to active streaming`() {
        assertTrue(shouldAnimateToolCallEntrance(isStreaming = true))
        assertFalse(shouldAnimateToolCallEntrance(isStreaming = false))
    }

    @Test
    fun `tool call entrance animation is claimed synchronously`() {
        clearToolCallEntranceAnimationHistoryForTest()

        assertTrue(shouldRunToolCallEntranceAnimation(animateEntrance = true, key = "tool|call-a"))
        assertFalse(shouldRunToolCallEntranceAnimation(animateEntrance = true, key = "tool|call-a"))

        assertFalse(shouldRunToolCallEntranceAnimation(animateEntrance = false, key = "tool|call-b"))
        assertTrue(shouldRunToolCallEntranceAnimation(animateEntrance = true, key = "tool|call-b"))
    }

    @Test
    fun `multiple tool calls use compact group renderer`() {
        assertFalse(shouldUseCompactToolCallGroup(emptyList()))
        assertFalse(
            shouldUseCompactToolCallGroup(
                listOf(UiToolCall(name = "Bash", arguments = "{}", result = null))
            )
        )
        assertTrue(
            shouldUseCompactToolCallGroup(
                listOf(
                    UiToolCall(name = "Bash", arguments = "{\"command\":\"a\"}", result = null),
                    UiToolCall(name = "Bash", arguments = "{\"command\":\"b\"}", result = null),
                )
            )
        )
    }

    @Test
    fun `copy text preserves exact raw tool result`() {
        val rawResult = """{"ok":true,"count":2}
[31mraw stderr[0m
| not | necessarily | a rendered table |"""
        val message = UiMessage(
            id = "tool-message",
            role = "assistant",
            content = "",
            timestamp = "2026-05-09T00:00:00Z",
            toolCalls = listOf(
                UiToolCall(
                    name = "Bash",
                    arguments = "{\"command\":\"run\"}",
                    result = rawResult,
                    status = "success",
                )
            ),
        )

        assertEquals(
            "Tool: Bash\nArguments:\n{\"command\":\"run\"}\nResult:\n$rawResult",
            buildMessageCopyText(message),
        )
    }
}
