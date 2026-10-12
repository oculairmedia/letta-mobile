package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.feature.chat.screen.ChatMessageSearch
import com.letta.mobile.feature.chat.screen.chatSearchResultKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `message hits become the header search's rows and map back to their hit`() {
        val current = searchResult(messageId = "m-current", content = "needle here").copy(conversationId = "c-now")
        val older = searchResult(messageId = "m-older", content = "older needle").copy(conversationId = "c-old")
        val conversations = listOf(
            Conversation(id = ConversationId("c-old"), agentId = AgentId("agent-1"), summary = "Planning"),
        )

        val sections = ChatMessageSearch.sections("needle", listOf(current, older), conversations, currentConversationId = "c-now")

        val rows = sections.single().rows
        assertEquals(ChatMessageSearch.SECTION_TITLE, sections.single().title)
        assertEquals(listOf("needle here", "older needle"), rows.map { it.label })
        assertEquals("Current conversation", rows[0].sublabel)
        assertEquals("Previous conversation - Planning", rows[1].sublabel)
        assertEquals(older, ChatMessageSearch.resultFor(rows[1].id, listOf(current, older)))
        assertTrue(ChatMessageSearch.sections("needle", emptyList(), conversations, null).isEmpty())
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
