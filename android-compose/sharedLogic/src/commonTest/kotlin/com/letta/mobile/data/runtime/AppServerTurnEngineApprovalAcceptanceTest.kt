package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.ApprovalSubmission
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

        turn.client.emit(frames.approvalControlRequest())
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

        assertEquals(ApprovalSubmitResult.Accepted, engine.submitApprovalResponse(submission("approval-1", allow)))

        client.approvalAck = InputAckFixture.rejected(APPROVAL_NOT_PENDING_ERROR)
        val rejected = engine.submitApprovalResponse(submission("approval-2", allow))
        assertEquals(ApprovalSubmitResult.Rejected(APPROVAL_NOT_PENDING_ERROR), rejected)

        client.supportsAck = false
        val unacknowledged = engine.submitApprovalResponse(submission("approval-3", allow))
        assertEquals(ApprovalSubmitResult.Unacknowledged("unsupported"), unacknowledged)
        assertEquals("approval-3", client.plainInputs.single().approvalRequestId())
    }

    @Test
    fun replayedControlRequestIsReansweredWithCachedDecisionAndNoSecondCard() = runTest {
        val turn = startTurn(AppServerPermissionMode.Standard)
        turn.client.emit(frames.approvalControlRequest())
        runCurrent()
        assertEquals(1, turn.drafts.approvalCards())

        val result = turn.engine.submitApprovalResponse(submission("approval-1", deny))
        assertEquals(ApprovalSubmitResult.Accepted, result)
        turn.client.emit(frames.approvalControlRequest())
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
        turn.client.emit(frames.approvalControlRequest())
        runCurrent()
        turn.client.approvalAck = InputAckFixture.rejected(APPROVAL_NOT_PENDING_ERROR)

        val result = turn.engine.submitApprovalResponse(submission("approval-1", allow))
        assertIs<ApprovalSubmitResult.Rejected>(result)
        turn.client.emit(frames.approvalControlRequest())
        runCurrent()

        assertEquals(1, turn.client.approvalInputs().size, "a rejected decision is forgotten, not re-sent")
        turn.job.cancel()
    }

    @Test
    fun autoAllowReplyRejectedAsNoLongerPendingIsAlreadyResolvedAndClearsGate() = runTest {
        val turn = startTurn(AppServerPermissionMode.Standard)
        turn.client.emit(frames.userInputControlRequest())
        runCurrent()
        assertEquals(TEST_APPROVAL_REQUEST_ID, turn.engine.userInputApprovalId(USER_INPUT_TOOL_CALL_ID))
        turn.client.approvalAck = InputAckFixture.rejected(APPROVAL_NOT_PENDING_ERROR)

        val result = turn.engine.submitApprovalResponse(streamDeltaAutoAllow(TEST_APPROVAL_REQUEST_ID))
        turn.engine.releaseUserInputGateUnlessRejected(result, USER_INPUT_TOOL_CALL_ID, TEST_APPROVAL_REQUEST_ID)

        assertEquals(ApprovalSubmitResult.Accepted, result, "the server already resolved it")
        assertNull(turn.engine.userInputApprovalId(USER_INPUT_TOOL_CALL_ID), "the gate is cleared as for an accepted reply")
        turn.job.cancel()
    }

    @Test
    fun controlRequestRejectedAsNoLongerPendingIsStillARejection() = runTest {
        val turn = startTurn(AppServerPermissionMode.Standard)
        turn.client.emit(frames.userInputControlRequest())
        runCurrent()
        turn.client.approvalAck = InputAckFixture.rejected(APPROVAL_NOT_PENDING_ERROR)

        val controlRequestAnswer = streamDeltaAutoAllow(TEST_APPROVAL_REQUEST_ID).copy(answersStreamDelta = false)
        val result = turn.engine.submitApprovalResponse(controlRequestAnswer)
        turn.engine.releaseUserInputGateUnlessRejected(result, USER_INPUT_TOOL_CALL_ID, TEST_APPROVAL_REQUEST_ID)

        assertEquals(ApprovalSubmitResult.Rejected(APPROVAL_NOT_PENDING_ERROR), result)
        assertEquals(TEST_APPROVAL_REQUEST_ID, turn.engine.userInputApprovalId(USER_INPUT_TOOL_CALL_ID), "the gate stays open")
        turn.job.cancel()
    }

    @Test
    fun streamDeltaReplyRejectedForAnotherReasonIsStillARejection() = runTest {
        val client = TurnEngineTestAckingClient(frames, InputAckFixture.Started)
        client.approvalAck = InputAckFixture.rejected("Destination runtime is already processing")
        val engine = engineFor(client, AppServerPermissionMode.Unrestricted)

        val result = engine.submitApprovalResponse(streamDeltaAutoAllow("approval-2"))

        assertEquals(ApprovalSubmitResult.Rejected("Destination runtime is already processing"), result)
    }

    @Test
    fun unrestrictedApprovalRequestMessageSendsNoReply() = runTest {
        val turn = startTurn(AppServerPermissionMode.Unrestricted)
        turn.client.emit(frames.approvalRequestMessage(toolName = "Bash"))
        runCurrent()

        assertTrue(turn.client.approvalInputs().isEmpty(), "no approval_response for an informational delta")
        assertTrue(turn.client.plainInputs.none { it.approvalRequestId() != null })
        val toolCall = turn.drafts.mapNotNull { it.payload as? RuntimeEventPayload.ToolCallObserved }.single()
        assertEquals("tool-call-1", toolCall.toolCallId.value)
        assertEquals(0, turn.drafts.approvalCards())
        turn.job.cancel()
    }

    @Test
    fun unrestrictedControlRequestIsStillAnswered() = runTest {
        val turn = startTurn(AppServerPermissionMode.Unrestricted)
        turn.client.emit(frames.approvalControlRequest())
        runCurrent()

        assertEquals(listOf(TEST_APPROVAL_REQUEST_ID), turn.client.approvalInputs().map { it.approvalRequestId() })
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

        const val USER_INPUT_TOOL_CALL_ID = "tool-call-ask-1"

        fun submission(approvalRequestId: String, decision: AppServerApprovalResponseDecision) =
            ApprovalSubmission(runtime, approvalRequestId, decision)

        fun streamDeltaAutoAllow(approvalRequestId: String) = ApprovalSubmission(
            runtime,
            approvalRequestId,
            AppServerApprovalResponseDecision.Allow(message = "Approved by default mobile policy."),
            source = "auto_allow",
            answersStreamDelta = true,
            toolName = "Bash",
        )

        fun TurnEngineTestFrames.userInputControlRequest() = approvalControlRequest().copy(
            request = buildJsonObject {
                put("subtype", "can_use_tool")
                put("tool_name", "AskUserQuestion")
                put("tool_call_id", USER_INPUT_TOOL_CALL_ID)
                put("input", buildJsonObject { put("question", "pick one") })
            },
        )
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-1:conv-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hey"),
        )
    }
}
