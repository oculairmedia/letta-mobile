package com.letta.mobile.ui.shell.pages

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.data.presence.runningAgentIds
import kotlin.test.Test
import kotlin.test.assertEquals

/** Home's fleet from a host's backend conversations (Android, the browser), as desktop folds its own. */
class ShellHomeFleetTest {
    private val roster = listOf(Agent(id = AgentId("ada"), name = "Ada"), Agent(id = AgentId("bo"), name = "Bo"))

    @Test
    fun backendConversationsBecomeTheFleetNamedFromTheRoster() {
        val fleet = shellHomeFleet(
            conversations = listOf(
                Conversation(id = ConversationId("c1"), agentId = AgentId("ada"), summary = "Plan", lastMessageAt = "2026-10-22T10:00:00Z"),
                Conversation(
                    id = ConversationId("c2"),
                    agentId = AgentId("bo"),
                    agentName = "Bo (named)",
                    lastMessageAt = "2026-10-22T11:00:00Z",
                ),
                Conversation(id = ConversationId("c3"), agentId = AgentId("ada"), hidden = true),
            ),
            roster = roster,
            runningAgentIds = setOf("bo"),
        )

        assertEquals(listOf("c1", "c2"), fleet.conversations.map { it.id })
        assertEquals(listOf("Ada", "Bo (named)"), fleet.conversations.map { it.agentName })
        assertEquals(roster, fleet.roster)
        assertEquals(setOf("bo"), fleet.runningAgentIds)
    }

    @Test
    fun anAgentMidTurnIsRunning() {
        val presence = mapOf(
            "ada" to AgentPresence(activity = AgentActivityKind.THINKING),
            "bo" to AgentPresence.IDLE,
            "cy" to AgentPresence(activity = AgentActivityKind.SPEAKING),
        )
        assertEquals(setOf("ada", "cy"), presence.runningAgentIds())
    }
}
