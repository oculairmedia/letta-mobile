package com.letta.mobile.data.presence

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-te3n1.1: subagents reach the run registry through one shared path on both
 * platforms - a running foreground subagent makes its conversation's tool DELEGATING, and a
 * background dream marks the agent dreaming without changing the turn (letta-mobile-iczm7).
 */
class SubagentPresenceTest {
    private val registry = ConversationRunRegistry()
    private var now = 0L
    private val publisher = ConversationRunPhasePublisher(registry) { ++now }

    private fun entry(id: String, type: String, status: String = "running", conversation: String? = "c1", agent: String? = "a1") =
        SubagentEntry(
            toolCallId = id,
            subagentType = type,
            status = status,
            parentAgentId = agent,
            parentConversationId = conversation,
        )

    @Test
    fun aRunningForegroundSubagentMakesTheToolDelegating() {
        publisher.onEvent(RunScope("c1", "a1"), RuntimeEventPayload.ToolCallObserved(ToolCallId("t1"), ToolName("Task")))
        publisher.setSubagents(listOf(entry("s1", "General-purpose")))
        assertEquals(RunPhase.DELEGATING, registry.runs.value.getValue("c1").phase)
        assertEquals(AgentActivityKind.DELEGATING, registry.runs.value.presenceByAgent().getValue("a1").activity)

        publisher.setSubagents(emptyList())
        assertEquals(RunPhase.WORKING, registry.runs.value.getValue("c1").phase, "the list dropped c1, so its count went back to none")
    }

    @Test
    fun aBackgroundRunIsADreamAndNeverChangesTheTurn() {
        publisher.onEvent(RunScope("c1", "a1"), RuntimeEventPayload.LocalUserAppend("l1", "hi"))
        val finished = entry("s2", "Reflection", status = "completed", agent = "a2", conversation = "c2")
        publisher.setSubagents(listOf(entry("s1", "Reflection"), finished))
        val presence = registry.runs.value.presenceByAgent()
        assertTrue(presence.getValue("a1").dreaming)
        assertEquals(AgentActivityKind.THINKING, presence.getValue("a1").activity)
        assertFalse(presence.getValue("a2").dreaming, "a finished dream is not a dream")
    }

    @Test
    fun anAgentWithNoTurnStillDreams() {
        publisher.setSubagents(listOf(entry("s1", "Memory", conversation = "c9", agent = "a9")))
        val presence = registry.runs.value.presenceByAgent().getValue("a9")
        assertTrue(presence.dreaming)
        assertEquals(AgentActivityKind.IDLE, presence.activity)
    }

    @Test
    fun entriesWithoutAParentScopeAreLeftOut() {
        publisher.setSubagents(listOf(entry("s1", "Reflection", conversation = null), entry("s2", "Reflection", agent = " ")))
        assertTrue(registry.runs.value.isEmpty())
    }

    @Test
    fun activityCountsOnlyRunningEntries() {
        val activity = SubagentActivity.of(
            listOf(entry("s1", "General-purpose"), entry("s2", "General-purpose", status = "completed"), entry("s3", "Reflection")),
        )
        assertEquals(SubagentActivity(running = 1, dreaming = true), activity)
    }

    @Test
    fun interruptingIsPublishedForABusyRun() {
        publisher.onEvent(RunScope("c1", "a1"), RuntimeEventPayload.LocalUserAppend("l1", "hi"))
        publisher.markInterrupting(RunScope("c1", "a1"))
        assertEquals(RunPhase.INTERRUPTING, registry.runs.value.getValue("c1").phase)
    }
}
