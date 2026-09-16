package com.letta.mobile.desktop.chat

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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
        fun emit(payload: RuntimeEventPayload) = relay.emit("conv-1", "agent-0", payload)
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

    private fun TestScope.testController(gateway: DesktopChatGateway): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            gatewayFactory = { gateway },
            timelinePersistence = noOpDesktopTimelinePersistence,
        )
}
