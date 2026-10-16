package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.RunPhase
import com.letta.mobile.data.presence.presenceByAgent
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop's half of the run-phase pipeline: the gateway's runtime events reach the window's
 * registry as phases, so the shell's mascot reads WORKING from the same reducer Android uses
 * instead of a desktop-only boolean resolver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerRunPhasesTest {

    private class EventEmittingGateway : FakeDesktopChatGateway(), DesktopRuntimeEventSource {
        private val relay = DesktopRuntimeEventRelay()
        override val runtimeEvents: SharedFlow<ScopedDesktopRuntimeEvent> = relay.runtimeEvents
        suspend fun emit(payload: RuntimeEventPayload) = relay.emit("conv-1", "agent-0", payload)
    }

    @Test
    fun aToolCallOnTheGatewayShowsUpAsWorkingInTheRegistry() = runTest {
        val gateway = EventEmittingGateway()
        val controller = testController(gateway)
        controller.start()
        runCurrent()

        gateway.emit(RuntimeEventPayload.ToolCallObserved(ToolCallId("t1"), ToolName("grep")))
        runCurrent()

        val run = controller.runs.value.getValue("conv-1")
        assertEquals(RunPhase.WORKING, run.phase)
        assertEquals("grep", run.toolName)
        assertEquals(AgentActivityKind.WORKING, controller.runs.value.presenceByAgent().getValue("agent-0").activity)

        gateway.emit(RuntimeEventPayload.ToolReturnObserved(ToolCallId("t1"), ToolExecutionStatus.Succeeded, "ok"))
        runCurrent()
        assertEquals(RunPhase.QUEUED, controller.runs.value.getValue("conv-1").phase)

        controller.close()
    }

    @Test
    fun aParkedApprovalIsAttributedToItsOwnConversation() = runTest {
        val gateway = EventEmittingGateway()
        val controller = testController(gateway)
        controller.start()
        runCurrent()

        gateway.emit(
            RuntimeEventPayload.ApprovalRequested(
                com.letta.mobile.runtime.ToolApprovalRequest(
                    approvalId = com.letta.mobile.runtime.ToolApprovalId("ap-1"),
                    callId = ToolCallId("t1"),
                    toolName = ToolName("Bash"),
                    prompt = "rm -rf",
                ),
            ),
        )
        runCurrent()

        val presence = controller.runs.value.presenceByAgent()
        assertEquals(true, presence.getValue("agent-0").awaitingApproval)

        controller.close()
    }

    @Test
    fun theWindowsSubagentsMakeAToolDelegatingAndADreamDream() = runTest {
        // letta-mobile-te3n1.1: DELEGATING and dreaming are produced on desktop, through the shared publisher.
        val gateway = EventEmittingGateway()
        val controller = testController(gateway)
        controller.start()
        runCurrent()

        gateway.emit(RuntimeEventPayload.ToolCallObserved(ToolCallId("t1"), ToolName("Task")))
        runCurrent()
        controller.publishSubagents(
            listOf(
                SubagentEntry(toolCallId = "s1", subagentType = "General-purpose", status = "running", parentAgentId = "agent-0", parentConversationId = "conv-1"),
                SubagentEntry(toolCallId = "s2", subagentType = "Reflection", status = "running", parentAgentId = "agent-0", parentConversationId = "conv-1"),
            ),
        )

        assertEquals(RunPhase.DELEGATING, controller.runs.value.getValue("conv-1").phase)
        val presence = controller.runs.value.presenceByAgent().getValue("agent-0")
        assertEquals(AgentActivityKind.DELEGATING, presence.activity)
        assertEquals(true, presence.dreaming)

        controller.close()
    }

    @Test
    fun stoppingARunMarksItInterrupting() = runTest {
        val gateway = EventEmittingGateway()
        val controller = testController(gateway)
        controller.start()
        runCurrent()

        gateway.emit(RuntimeEventPayload.ToolCallObserved(ToolCallId("t1"), ToolName("grep")))
        runCurrent()
        // Every phase the conversation passes through, unconflated: a stop with nothing to abort
        // lands its terminal at once, so INTERRUPTING is a step on the way, not the end state.
        val phases = mutableListOf<RunPhase?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.runs.collect { phases += it["conv-1"]?.phase }
        }
        controller.stopActiveRun("conv-1")
        runCurrent()

        assertTrue(RunPhase.INTERRUPTING in phases, "the stop was published as INTERRUPTING: $phases")
        controller.close()
    }

    private fun TestScope.testController(gateway: DesktopChatGateway): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            gatewayFactory = { gateway },
        )
}
