package com.letta.mobile.data.presence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationRunRegistryTest {
    @Test
    fun publishAndClearTrackRunningConversations() {
        val registry = ConversationRunRegistry()
        registry.publish(ConversationRunState("c1", "a1", phase = RunPhase.REASONING))
        registry.publish(ConversationRunState("c2", "a1", phase = RunPhase.IDLE))
        assertEquals(setOf("c1"), registry.runningConversationIds())
        registry.clear("c1")
        assertEquals(emptySet(), registry.runningConversationIds())
    }

    @Test
    fun presenceFoldsAcrossAnAgentsConversations() {
        val runs = mapOf(
            "c1" to ConversationRunState("c1", "a1", phase = RunPhase.REASONING),
            "c2" to ConversationRunState("c2", "a1", phase = RunPhase.RESPONDING, userTyping = true),
            "c3" to ConversationRunState("c3", "a2", phase = RunPhase.FAILED),
        )
        val presence = runs.presenceByAgent()
        assertEquals(AgentActivityKind.SPEAKING, presence.getValue("a1").activity)
        assertEquals(true, presence.getValue("a1").userTyping)
        assertEquals(AgentActivityKind.IDLE, presence.getValue("a2").activity)
        assertEquals(true, presence.getValue("a2").error)
        assertNull(presence["a3"])
    }

    @Test
    fun theToolNameBelongsToThePhaseThatWonTheActivity() {
        val working = ConversationRunState("c1", "a1", phase = RunPhase.WORKING, toolName = "grep")
        val delegating = ConversationRunState("c2", "a1", phase = RunPhase.DELEGATING, toolName = "Task")
        // A delegating conversation outranks a working one, so its tool is the one named.
        val presence = mapOf("c1" to working, "c2" to delegating).presenceByAgent().getValue("a1")
        assertEquals(AgentActivityKind.DELEGATING, presence.activity)
        assertEquals("Task", presence.toolName)
        // Speaking names no tool, whatever else the agent has in flight.
        val speaking = ConversationRunState("c3", "a1", phase = RunPhase.RESPONDING)
        assertNull(mapOf("c1" to working, "c3" to speaking).presenceByAgent().getValue("a1").toolName)
    }

    @Test
    fun thinkingWhenRunningWithoutTokens() {
        val presence = mapOf("c1" to ConversationRunState("c1", "a1", phase = RunPhase.QUEUED)).presenceByAgent()
        assertEquals(AgentActivityKind.THINKING, presence.getValue("a1").activity)
    }

    @Test
    fun toolWorkOutranksReasoningAndRespondingOutranksBoth() {
        val reasoning = ConversationRunState("c1", "a1", phase = RunPhase.REASONING)
        val working = ConversationRunState("c2", "a1", phase = RunPhase.WORKING, toolName = "grep")
        val delegating = ConversationRunState("c3", "a1", phase = RunPhase.DELEGATING, toolName = "Task", subagentCount = 2)
        val responding = ConversationRunState("c4", "a1", phase = RunPhase.RESPONDING)

        val whileWorking = mapOf("c1" to reasoning, "c2" to working).presenceByAgent().getValue("a1")
        assertEquals(AgentActivityKind.WORKING, whileWorking.activity)
        assertEquals("grep", whileWorking.toolName)

        val whileDelegating = mapOf("c1" to reasoning, "c2" to working, "c3" to delegating)
            .presenceByAgent().getValue("a1")
        assertEquals(AgentActivityKind.DELEGATING, whileDelegating.activity)

        val whileResponding = mapOf("c3" to delegating, "c4" to responding).presenceByAgent().getValue("a1")
        assertEquals(AgentActivityKind.SPEAKING, whileResponding.activity)
    }

    @Test
    fun approvalIsPerConversationNotPerSelection() {
        val presence = mapOf(
            "c1" to ConversationRunState("c1", "a1", phase = RunPhase.AWAITING_INPUT, toolName = "Bash"),
            "c2" to ConversationRunState("c2", "a2", phase = RunPhase.IDLE),
        ).presenceByAgent()
        assertEquals(true, presence.getValue("a1").awaitingApproval)
        assertEquals(false, presence.getValue("a2").awaitingApproval)
    }
}
