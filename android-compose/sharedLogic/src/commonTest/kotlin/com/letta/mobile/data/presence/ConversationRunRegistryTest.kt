package com.letta.mobile.data.presence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationRunRegistryTest {
    @Test
    fun publishAndClearTrackRunningConversations() {
        val registry = ConversationRunRegistry()
        registry.publish(ConversationRunState("c1", "a1", running = true))
        registry.publish(ConversationRunState("c2", "a1", running = false))
        assertEquals(setOf("c1"), registry.runningConversationIds())
        registry.clear("c1")
        assertEquals(emptySet(), registry.runningConversationIds())
    }

    @Test
    fun presenceFoldsAcrossAnAgentsConversations() {
        val runs = mapOf(
            "c1" to ConversationRunState("c1", "a1", running = true, streamingTokens = false),
            "c2" to ConversationRunState("c2", "a1", running = true, streamingTokens = true, userTyping = true),
            "c3" to ConversationRunState("c3", "a2", running = false, error = true),
        )
        val presence = runs.presenceByAgent()
        assertEquals(AgentActivityKind.SPEAKING, presence.getValue("a1").activity)
        assertEquals(true, presence.getValue("a1").userTyping)
        assertEquals(AgentActivityKind.IDLE, presence.getValue("a2").activity)
        assertEquals(true, presence.getValue("a2").error)
        assertNull(presence["a3"])
    }

    @Test
    fun thinkingWhenRunningWithoutTokens() {
        val presence = mapOf("c1" to ConversationRunState("c1", "a1", running = true)).presenceByAgent()
        assertEquals(AgentActivityKind.THINKING, presence.getValue("a1").activity)
    }
}
