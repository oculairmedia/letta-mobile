package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.ParsedSearchMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AgentScaffoldSearchTest {

    @Test
    fun `chat search result keys remain unique for duplicate message hits`() {
        val first = searchResult(content = "needle in first chunk")
        val second = searchResult(content = "needle in second chunk")

        assertNotEquals(
            chatSearchResultKey(first, index = 0),
            chatSearchResultKey(second, index = 1),
        )
    }

    @Test
    fun `chat search result key preserves message identity prefix`() {
        val result = searchResult(messageId = "message-a94f2287-bc86-4adf-bfe4-9a0386649251")

        assertEquals(
            "chat-search-message-a94f2287-bc86-4adf-bfe4-9a0386649251-0",
            chatSearchResultKey(result, index = 0),
        )
    }

    private fun searchResult(
        messageId: String = "message-duplicate",
        content: String = "needle hit",
    ) = ParsedSearchMessage(
        messageId = messageId,
        agentId = "agent-1",
        role = "assistant",
        content = content,
        date = "2026-05-08T12:00:00Z",
        conversationId = "conversation-1",
    )
}
