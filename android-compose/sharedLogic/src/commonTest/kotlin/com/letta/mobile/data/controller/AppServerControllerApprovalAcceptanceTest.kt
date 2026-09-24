package com.letta.mobile.data.controller

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.FakeAppServerTestClient
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-qygvv.5: [DefaultAppServerController.submitApproval] awaits
 * `input_accepted` and returns a typed result, and a `control_request` the server
 * replays for a decided request is re-answered with the cached decision through
 * the controller's fanout instead of being dropped or shown twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerControllerApprovalAcceptanceTest {

    @Test
    fun submitApprovalAcceptedCarriesRequestIdAndReturnsAccepted() = runTest {
        val client = AckingControllerClient()
        val controller = startedController(client)
        try {
            val result = controller.submit(approve = true)

            assertEquals(ApprovalSubmitResult.Accepted, result)
            val sent = client.approvalInputs().single()
            assertEquals("req-ack", sent.requestId)
            val payload = assertIs<AppServerInputPayload.ApprovalResponse>(sent.payload)
            assertEquals("approval-1", payload.requestId)
            assertIs<AppServerApprovalResponseDecision.Allow>(payload.decision)
        } finally {
            controller.close()
        }
    }

    @Test
    fun submitApprovalRejectedReturnsServerErrorAndIsNotReplayed() = runTest {
        val client = AckingControllerClient().apply { approvalAccepted = false }
        val controller = startedController(client)
        try {
            val result = controller.submit(approve = true)

            assertEquals(ApprovalSubmitResult.Rejected("Approval request is no longer pending"), result)
            client.emit(controlRequest("approval-1"))
            runCurrent()
            assertEquals(1, client.approvalInputs().size, "a rejected decision is not re-sent on replay")
        } finally {
            controller.close()
        }
    }

    @Test
    fun submitApprovalWithoutAckReturnsUnacknowledged() = runTest {
        val client = AckingControllerClient().apply { supportsAck = false }
        val controller = startedController(client)
        try {
            val result = controller.submit(approve = false)

            assertEquals(ApprovalSubmitResult.Unacknowledged("unsupported"), result)
            val payload = assertIs<AppServerInputPayload.ApprovalResponse>(client.plainInputs.single().payload)
            assertIs<AppServerApprovalResponseDecision.Deny>(payload.decision)
        } finally {
            controller.close()
        }
    }

    @Test
    fun replayedControlRequestIsReansweredWithCachedDecisionAndNoSecondCard() = runTest {
        val client = AckingControllerClient()
        val controller = startedController(client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { controller.runTurn(command).collect { drafts += it } }
        try {
            runCurrent()
            client.emit(controlRequest("approval-1"))
            runCurrent()
            assertEquals(1, drafts.approvalCards())

            assertEquals(ApprovalSubmitResult.Accepted, controller.submit(approve = false, reason = "not now"))
            client.emit(controlRequest("approval-1"))
            runCurrent()

            val answers = client.approvalInputs()
            assertEquals(2, answers.size, "the replay is re-answered once")
            val decisions = answers.map { assertIs<AppServerInputPayload.ApprovalResponse>(it.payload).decision }
            assertEquals(AppServerApprovalResponseDecision.Deny(message = "not now"), decisions.distinct().single())
            assertEquals(1, drafts.approvalCards(), "no second approval card")
        } finally {
            turn.cancel()
            controller.close()
        }
    }

    @Test
    fun replayWithNoActiveTurnIsReansweredFromCache() = runTest {
        // The reconnect path replays pending approvals before any turn subscribes.
        val client = AckingControllerClient()
        val controller = startedController(client)
        try {
            assertEquals(ApprovalSubmitResult.Accepted, controller.submit(approve = true))
            client.emit(controlRequest("approval-1"))
            runCurrent()

            assertEquals(2, client.approvalInputs().size)
        } finally {
            controller.close()
        }
    }

    private suspend fun TestScope.startedController(client: AckingControllerClient): DefaultAppServerController {
        val controller = DefaultAppServerController(
            client = client,
            requestIdFactory = { "req-ack" },
            parentCoroutineContext = Dispatchers.Unconfined,
        )
        controller.startRuntime(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            mode = AppServerPermissionMode.Standard,
        )
        runCurrent()
        return controller
    }

    private suspend fun DefaultAppServerController.submit(approve: Boolean, reason: String? = null) =
        submitApproval(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            approvalRequestId = "approval-1",
            approve = approve,
            reason = reason,
        )

    private fun List<RuntimeEventDraft>.approvalCards() = count { it.payload is RuntimeEventPayload.ApprovalRequested }

    private companion object {
        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
        )

        fun controlRequest(requestId: String) = AppServerInboundFrame.ControlRequest(
            requestId = requestId,
            request = buildJsonObject {
                put("subtype", "can_use_tool")
                put("tool_name", "searxng_web_search")
                put("tool_call_id", "tool-call-1")
                put("input", buildJsonObject { put("query", "iroh") })
            },
            agentId = "agent-1",
            conversationId = "conv-1",
        )
    }

    private class AckingControllerClient : FakeAppServerTestClient() {
        val acknowledgedInputs = mutableListOf<AppServerCommand.Input>()
        val plainInputs = mutableListOf<AppServerCommand.Input>()
        var supportsAck = true
        var approvalAccepted = true

        fun approvalInputs() = acknowledgedInputs.filter { it.payload is AppServerInputPayload.ApprovalResponse }

        override suspend fun input(command: AppServerCommand.Input) {
            plainInputs += command
        }

        override suspend fun inputAwaitingAcceptance(
            command: AppServerCommand.Input,
        ): AppServerInboundFrame.InputAccepted {
            if (!supportsAck) throw UnsupportedOperationException("no ack")
            acknowledgedInputs += command
            val accepted = command.payload !is AppServerInputPayload.ApprovalResponse || approvalAccepted
            return AppServerInboundFrame.InputAccepted(
                requestId = requireNotNull(command.requestId),
                runtime = command.runtime,
                accepted = accepted,
                disposition = if (accepted) "started" else null,
                error = if (accepted) null else "Approval request is no longer pending",
            )
        }
    }
}
