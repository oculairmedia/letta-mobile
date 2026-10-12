package com.letta.mobile.data.controller

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.APPROVAL_NOT_PENDING_ERROR
import com.letta.mobile.data.runtime.InputAckFixture
import com.letta.mobile.data.runtime.TEST_APPROVAL_REQUEST_ID
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.letta.mobile.data.runtime.TurnEngineTestAckingClient
import com.letta.mobile.data.runtime.TurnEngineTestFrames
import com.letta.mobile.data.runtime.approvalCards
import com.letta.mobile.data.runtime.approvalDecision
import com.letta.mobile.data.runtime.approvalRequestId
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-qygvv.5: [DefaultAppServerController.submitApproval] awaits
 * `input_accepted` and returns a typed result, and a `control_request` the server
 * replays for a decided request is re-answered with the cached decision through
 * the controller's fanout instead of being dropped or shown twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerControllerApprovalAcceptanceTest {

    @Test
    fun submitApprovalAcceptedCarriesRequestIdAndReturnsAccepted() = withController { client, controller ->
        val result = controller.submit(approve = true)

        assertEquals(ApprovalSubmitResult.Accepted, result)
        val sent = client.approvalInputs().single()
        assertEquals("req-ack", sent.requestId)
        assertEquals("approval-1", sent.approvalRequestId())
        assertIs<AppServerApprovalResponseDecision.Allow>(sent.approvalDecision())
    }

    @Test
    fun submitApprovalRejectedReturnsServerErrorAndIsNotReplayed() = withController(
        configure = { approvalAck = InputAckFixture.rejected(APPROVAL_NOT_PENDING_ERROR) },
    ) { client, controller ->
        val result = controller.submit(approve = true)

        assertEquals(ApprovalSubmitResult.Rejected(APPROVAL_NOT_PENDING_ERROR), result)
        client.emit(frames.approvalControlRequest())
        runCurrent()
        assertEquals(1, client.approvalInputs().size, "a rejected decision is not re-sent on replay")
    }

    @Test
    fun submitApprovalWithoutAckReturnsUnacknowledged() = withController(
        configure = { supportsAck = false },
    ) { client, controller ->
        val result = controller.submit(approve = false)

        assertEquals(ApprovalSubmitResult.Unacknowledged("unsupported"), result)
        assertIs<AppServerApprovalResponseDecision.Deny>(client.plainInputs.single().approvalDecision())
    }

    @Test
    fun replayedControlRequestIsReansweredWithCachedDecisionAndNoSecondCard() = withController { client, controller ->
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { controller.runTurn(command).collect { drafts += it } }
        runCurrent()
        client.emit(frames.approvalControlRequest())
        runCurrent()
        assertEquals(1, drafts.approvalCards())

        assertEquals(ApprovalSubmitResult.Accepted, controller.submit(approve = false, reason = "not now"))
        client.emit(frames.approvalControlRequest())
        runCurrent()

        val decisions = client.approvalInputs().map { it.approvalDecision() }
        assertEquals(2, decisions.size, "the replay is re-answered once")
        assertEquals(listOf(AppServerApprovalResponseDecision.Deny(message = "not now")), decisions.distinct())
        assertEquals(1, drafts.approvalCards(), "no second approval card")
        turn.cancel()
    }

    @Test
    fun replayWithNoActiveTurnIsReansweredFromCache() = withController { client, controller ->
        // The reconnect path replays pending approvals before any turn subscribes.
        assertEquals(ApprovalSubmitResult.Accepted, controller.submit(approve = true))
        client.emit(frames.approvalControlRequest())
        runCurrent()

        assertEquals(2, client.approvalInputs().size)
    }

    /** letta-mobile-bzvro.40: an Iroh client's always-allow is sent only for the request that offered it. */
    @Test
    fun alwaysAllowCarriesTheRuleForTheParkedRequestThatOfferedIt() = withController { client, controller ->
        val turn = launch { controller.runTurn(command).collect {} }
        runCurrent()
        client.emit(frames.approvalControlRequest().offering("rule-1"))
        runCurrent()

        val result = controller.submitApproval(alwaysAllow(approvalRequestId = TEST_APPROVAL_REQUEST_ID, rule = "rule-1"))

        assertEquals(ApprovalSubmitResult.Accepted, result)
        val decision = assertIs<AppServerApprovalResponseDecision.Allow>(client.approvalInputs().single().approvalDecision())
        assertEquals(listOf("rule-1"), decision.selectedPermissionSuggestionIds)
        turn.cancel()
    }

    @Test
    fun aRuleTheParkedRequestDoesNotOfferIsRefusedAndNothingIsSent() = withController { client, controller ->
        val turn = launch { controller.runTurn(command).collect {} }
        runCurrent()
        client.emit(frames.approvalControlRequest().offering("rule-1"))
        runCurrent()

        val wrongRule = controller.submitApproval(alwaysAllow(approvalRequestId = TEST_APPROVAL_REQUEST_ID, rule = "rule-2"))
        val wrongRequest = controller.submitApproval(alwaysAllow(approvalRequestId = "another-request", rule = "rule-1"))

        assertIs<ApprovalSubmitResult.Rejected>(wrongRule)
        assertIs<ApprovalSubmitResult.Rejected>(wrongRequest)
        assertEquals(emptyList(), client.approvalInputs())
        turn.cancel()
    }

    /** letta-mobile-bzvro.40: a client's later `runtime_start` naming its own default never loosens a chosen mode. */
    @Test
    fun aChosenModeIsPinnedAgainstALooserStart() = withController { _, controller ->
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")

        assertTrue(controller.changePermissionMode(runtime, AppServerPermissionMode.Strict))
        controller.startRuntime(AgentId("agent-1"), ConversationId("conv-1"), mode = AppServerPermissionMode.Unrestricted)

        assertEquals(AppServerPermissionMode.Strict, controller.permissionModeOf(runtime))
    }

    private fun AppServerInboundFrame.ControlRequest.offering(vararg rules: String) = copy(
        request = JsonObject(
            request + ("permission_suggestions" to buildJsonArray {
                rules.forEach { rule -> add(buildJsonObject { put("id", rule); put("text", "Allow $rule") }) }
            }),
        ),
    )

    private fun alwaysAllow(approvalRequestId: String, rule: String) = ControllerApprovalAnswer(
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conv-1"),
        approvalRequestId = approvalRequestId,
        approve = true,
        toolCallId = "tool-call-1",
        selectedSuggestionIds = listOf(rule),
    )

    private fun withController(
        configure: TurnEngineTestAckingClient.() -> Unit = {},
        body: suspend TestScope.(TurnEngineTestAckingClient, DefaultAppServerController) -> Unit,
    ) = runTest {
        val client = TurnEngineTestAckingClient(frames, InputAckFixture.Started).apply(configure)
        val controller = startedController(client)
        try {
            body(client, controller)
        } finally {
            controller.close()
        }
    }

    private suspend fun TestScope.startedController(client: TurnEngineTestAckingClient): DefaultAppServerController {
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

    private companion object {
        val frames = TurnEngineTestFrames(AppServerRuntimeScope("agent-1", "conv-1"))
        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
        )
    }
}
