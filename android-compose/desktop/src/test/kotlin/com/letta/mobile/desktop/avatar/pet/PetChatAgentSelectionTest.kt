package com.letta.mobile.desktop.avatar.pet

import com.letta.mobile.desktop.chat.DesktopConversationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PetChatAgentSelectionTest {

    private fun conversation(
        id: String,
        agentId: String? = "agent-$id",
        agentName: String = "Agent $id",
    ) = DesktopConversationSummary(
        id = id,
        title = "Conversation $id",
        agentName = agentName,
        updatedAtLabel = "now",
        lastMessagePreview = "…",
        agentId = agentId,
    )

    @Test
    fun picksNewestConversationAgent() {
        // Controller lists newest-first, so the head wins.
        val selected = selectPetChatAgent(
            listOf(
                conversation(id = "newest", agentId = "agent-newest", agentName = "Nova"),
                conversation(id = "older", agentId = "agent-older", agentName = "Old"),
            ),
        )
        assertEquals(PetChatAgent(agentId = "agent-newest", displayName = "Nova"), selected)
    }

    @Test
    fun skipsLeadingConversationsWithoutAgentId() {
        val selected = selectPetChatAgent(
            listOf(
                conversation(id = "legacy", agentId = null),
                conversation(id = "usable", agentId = "agent-usable", agentName = "Real"),
            ),
        )
        assertEquals(PetChatAgent(agentId = "agent-usable", displayName = "Real"), selected)
    }

    @Test
    fun fallsBackToAgentIdWhenNameBlank() {
        val selected = selectPetChatAgent(
            listOf(conversation(id = "x", agentId = "agent-x", agentName = "   ")),
        )
        assertEquals(PetChatAgent(agentId = "agent-x", displayName = "agent-x"), selected)
    }

    @Test
    fun returnsNullWhenNoConversationHasAnAgent() {
        assertNull(
            selectPetChatAgent(
                listOf(
                    conversation(id = "a", agentId = null),
                    conversation(id = "b", agentId = "  "),
                ),
            ),
        )
        assertNull(selectPetChatAgent(emptyList()))
    }
}
