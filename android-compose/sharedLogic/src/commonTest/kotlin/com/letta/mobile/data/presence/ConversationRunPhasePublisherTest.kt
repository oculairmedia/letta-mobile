package com.letta.mobile.data.presence

import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationRunPhasePublisherTest {
    private val registry = ConversationRunRegistry()
    private var now = 0L
    private val publisher = ConversationRunPhasePublisher(registry) { ++now }

    @Test
    fun anEventReachesTheRegistryAsAPhaseInTheSameCall() {
        publisher.onEvent("c1", "a1", RuntimeEventPayload.ToolCallObserved(ToolCallId("t1"), ToolName("grep")))
        val run = registry.runs.value.getValue("c1")
        assertEquals(RunPhase.WORKING, run.phase)
        assertEquals("grep", run.toolName)
        assertEquals(AgentActivityKind.WORKING, registry.runs.value.presenceByAgent().getValue("a1").activity)
    }

    @Test
    fun typingIsOrthogonalToThePhase() {
        publisher.onEvent("c1", "a1", RuntimeEventPayload.ToolCallObserved(ToolCallId("t1"), ToolName("grep")))
        publisher.setUserTyping("c1", "a1", true)
        val run = registry.runs.value.getValue("c1")
        assertEquals(RunPhase.WORKING, run.phase)
        assertEquals(true, run.userTyping)
    }

    @Test
    fun aRunCarriesOverToTheServerAssignedConversationId() {
        publisher.onEvent("agent:a1", "a1", RuntimeEventPayload.LocalUserAppend("local-1", "hi"))
        publisher.rekey("agent:a1", "conv-7", "a1")
        assertNull(registry.runs.value["agent:a1"])
        assertEquals(RunPhase.QUEUED, registry.runs.value.getValue("conv-7").phase)

        publisher.onEvent("conv-7", "a1", RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed))
        assertEquals(RunPhase.DONE, registry.runs.value.getValue("conv-7").phase)
    }

    @Test
    fun clearingForgetsTheConversation() {
        publisher.onEvent("c1", "a1", RuntimeEventPayload.LocalUserAppend("local-1", "hi"))
        publisher.clear("c1")
        assertNull(registry.runs.value["c1"])
    }
}
