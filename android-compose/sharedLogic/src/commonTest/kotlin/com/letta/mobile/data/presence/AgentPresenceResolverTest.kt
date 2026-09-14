package com.letta.mobile.data.presence

import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentPresenceResolverTest {
    private fun conv(id: String, agent: String) = ChatConversationSummary(
        id = id, title = id, agentName = agent, updatedAtLabel = "", lastMessagePreview = "", agentId = agent,
    )

    private val conversations = listOf(conv("c1", "a"), conv("c2", "b"), conv("c3", "a"))

    @Test
    fun `a running turn is thinking until tokens stream, then speaking`() {
        val thinking = AgentPresenceResolver.resolve(conversations, runningConversationId = "c1", streamingTokens = false, selectedConversationId = "c1", composerText = "")
        assertEquals(AgentActivityKind.THINKING, thinking.getValue("a").activity)
        assertNull(thinking["b"])
        val speaking = AgentPresenceResolver.resolve(conversations, runningConversationId = "c1", streamingTokens = true, selectedConversationId = "c1", composerText = "")
        assertEquals(AgentActivityKind.SPEAKING, speaking.getValue("a").activity)
    }

    @Test
    fun `typing reaches the selected conversation's agent only`() {
        val out = AgentPresenceResolver.resolve(conversations, runningConversationId = null, streamingTokens = false, selectedConversationId = "c2", composerText = "hel")
        assertEquals(AgentPresence(userTyping = true), out.getValue("b"))
        assertNull(out["a"])
    }

    @Test
    fun `approval and error flags land on the conversation's agent`() {
        val out = AgentPresenceResolver.resolve(
            conversations, runningConversationId = "c3", streamingTokens = false, selectedConversationId = null, composerText = "",
            approvalConversationIds = setOf("c3"), errorConversationId = "c2",
        )
        assertEquals(AgentPresence(activity = AgentActivityKind.THINKING, awaitingApproval = true), out.getValue("a"))
        assertEquals(AgentPresence(error = true), out.getValue("b"))
    }

    @Test
    fun `a conversation without an agent contributes nothing`() {
        val out = AgentPresenceResolver.resolve(listOf(conv("c9", "x").copy(agentId = null)), runningConversationId = "c9", streamingTokens = true, selectedConversationId = "c9", composerText = "y")
        assertEquals(emptyMap(), out)
    }
}
