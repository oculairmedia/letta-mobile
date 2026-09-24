package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.ApprovalSubmitResult
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-qygvv.5: approval responses sent by the engine (auto-approve and
 * [AppServerTurnEngine.submitApprovalResponse]) carry a `request_id`, await
 * `input_accepted`, and a replayed `control_request` for a decided request is
 * re-answered with the cached decision instead of surfacing a second card.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineApprovalAcceptanceTest {

    @Test
    fun autoApproveAwaitsInputAcceptedBeforeSurfacingToolCall() = runTest {
        val approvalAck = CompletableDeferred<AppServerInboundFrame.InputAccepted>()
        val turn = startTurn(AppServerPermissionMode.Unrestricted) { approvalAckGate = approvalAck }

        turn.client.emit(frames.approvalControlRequest("approval-1"))
        runCurrent()

        val sent = turn.client.approvalInputs().single()
        assertEquals("approval-1", (sent.payload as AppServerInputPayload.ApprovalResponse).requestId)
        assertNotNull(sent.requestId, "the approval response must carry a request_id")
        assertTrue(turn.drafts.none { it.payload is RuntimeEventPayload.ToolCallObserved }, "the tool card waits for the ack")

        approvalAck.complete(frames.inputAccepted(InputAckFixture.Started))
        runCurrent()

        assertTrue(turn.drafts.any { it.payload is RuntimeEventPayload.ToolCallObserved })
        assertEquals(0, turn.drafts.approvalCards())
        turn.job.cancel()
    }

    @Test
    fun submitApprovalResponseReturnsAcceptedRejectedAndUnacknowledged() = runTest {
        val client = TurnEngineTestAckingClient(frames, InputAckFixture.Started)
        val engine = engineFor(client, AppServerPermissionMode.Standard)

        assertEquals(ApprovalSubmitResult.Accepted, engine.submitApprovalResponse(runtime, "approval-1", allow))

        client.approvalAck = InputAckFixture.rejected(APPROVAL_NOT_PENDING_ERROR)
        val rejected = engine.submitApprovalResponse(runtime, "approval-2", allow)
        assertEquals(ApprovalSubmitResult.Rejected(APPROVAL_NOT_PENDING_ERROR), rejected)

        client.supportsAck = false
        val unacknowledged = engine.submitApprovalResponse(runtime, "approval-3", allow)
        assertEquals(ApprovalSubmitResult.Unacknowledged("unsupported"), unacknowledged)
        assertEquals("approval-3", client.plainInputs.single().approvalRequestId())
    }

    @Test
    fun replayedControlRequestIsReansweredWithCachedDecisionAndNoSecondCard() = runTest {
        val turn = startTurn(AppServerPermissionMode.Standard)
        turn.client.emit(frames.approvalControlRequest("approval-1"))
        runCurrent()
        assertEquals(1, turn.drafts.approvalCards())

        val result = turn.engine.submitApprovalResponse(runtime, "approval-1", deny)
        assertEquals(ApprovalSubmitResult.Accepted, result)
        turn.client.emit(frames.approvalControlRequest("approval-1"))
        runCurrent()

        val answers = turn.client.approvalInputs()
        assertEquals(2, answers.size, "the replay is re-answered once")
        assertEquals(listOf("approval-1"), answers.map { it.approvalRequestId() }.distinct())
        assertEquals(listOf(deny), answers.map { it.approvalDecision() }.distinct())
        assertEquals(1, turn.drafts.approvalCards(), "no second approval card")
        turn.job.cancel()
    }

    @Test
    fun rejectedDecisionIsNotReplayedFromCache() = runTest {
        val turn = startTurn(AppServerPermissionMode.Standard)
        turn.client.emit(frames.approvalControlRequest("approval-1"))
        runCurrent()
        turn.client.approvalAck = InputAckFixture.rejected(APPROVAL_NOT_PENDING_ERROR)

        val result = turn.engine.submitApprovalResponse(runtime, "approval-1", allow)
        assertIs<ApprovalSubmitResult.Rejected>(result)
        turn.client.emit(frames.approvalControlRequest("approval-1"))
        runCurrent()

        assertEquals(1, turn.client.approvalInputs().size, "a rejected decision is forgotten, not re-sent")
        turn.job.cancel()
    }

    private class RunningTurn(
        val client: TurnEngineTestAckingClient,
        val engine: AppServerTurnEngine,
        val drafts: List<RuntimeEventDraft>,
        val job: Job,
    )

    private fun TestScope.startTurn(
        mode: AppServerPermissionMode,
        configure: TurnEngineTestAckingClient.() -> Unit = {},
    ): RunningTurn {
        val client = TurnEngineTestAckingClient(frames, InputAckFixture.Started).apply(configure)
        val engine = engineFor(client, mode)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val job = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()
        return RunningTurn(client, engine, drafts, job)
    }

    private fun TestScope.engineFor(client: TurnEngineTestAckingClient, mode: AppServerPermissionMode) =
        AppServerTurnEngine(
            client = client,
            permissionMode = mode,
            turnIdleTimeoutMs = 60_000,
            terminalSettleQuietMs = 10,
            nowMs = { testScheduler.currentTime },
        )

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val frames = TurnEngineTestFrames(runtime)
        val allow = AppServerApprovalResponseDecision.Allow(message = "ok")
        val deny = AppServerApprovalResponseDecision.Deny(message = "not now")
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-1:conv-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hey"),
        )
    }
}
