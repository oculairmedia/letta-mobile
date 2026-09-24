package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.ApprovalSubmitResult
import com.letta.mobile.data.model.AgentId
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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
 * letta-mobile-qygvv.5: approval responses sent by the engine (auto-approve and
 * [AppServerTurnEngine.submitApprovalResponse]) carry a `request_id`, await
 * `input_accepted`, and a replayed `control_request` for a decided request is
 * re-answered with the cached decision instead of surfacing a second card.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineApprovalAcceptanceTest {

    @Test
    fun autoApproveAwaitsInputAcceptedBeforeSurfacingToolCall() = runTest {
        val client = ApprovalAckClient()
        val approvalAck = CompletableDeferred<AppServerInboundFrame.InputAccepted>()
        client.approvalAckGate = approvalAck
        val engine = engineFor(client, AppServerPermissionMode.Unrestricted)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()

        client.emit(controlRequest("approval-1"))
        runCurrent()

        val sent = client.approvalInputs().single()
        assertEquals("approval-1", (sent.payload as AppServerInputPayload.ApprovalResponse).requestId)
        assertTrue(sent.requestId != null, "the approval response must carry a request_id")
        assertTrue(
            drafts.none { it.payload is RuntimeEventPayload.ToolCallObserved },
            "the tool card waits for the approval's input_accepted",
        )

        approvalAck.complete(client.ackFor(sent, accepted = true))
        runCurrent()

        assertTrue(drafts.any { it.payload is RuntimeEventPayload.ToolCallObserved })
        assertTrue(drafts.none { it.payload is RuntimeEventPayload.ApprovalRequested })
        turn.cancel()
    }

    @Test
    fun submitApprovalResponseReturnsAcceptedRejectedAndUnacknowledged() = runTest {
        val client = ApprovalAckClient()
        val engine = engineFor(client, AppServerPermissionMode.Standard)
        val allow = AppServerApprovalResponseDecision.Allow(message = "ok")

        assertEquals(ApprovalSubmitResult.Accepted, engine.submitApprovalResponse(runtime, "approval-1", allow))

        client.approvalAccepted = false
        assertEquals(
            ApprovalSubmitResult.Rejected("Approval request is no longer pending"),
            engine.submitApprovalResponse(runtime, "approval-2", allow),
        )

        client.supportsAck = false
        assertEquals(
            ApprovalSubmitResult.Unacknowledged("unsupported"),
            engine.submitApprovalResponse(runtime, "approval-3", allow),
        )
        assertEquals("approval-3", (client.plainInputs.single().payload as AppServerInputPayload.ApprovalResponse).requestId)
    }

    @Test
    fun replayedControlRequestIsReansweredWithCachedDecisionAndNoSecondCard() = runTest {
        val client = ApprovalAckClient()
        val engine = engineFor(client, AppServerPermissionMode.Standard)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()

        client.emit(controlRequest("approval-1"))
        runCurrent()
        assertEquals(1, drafts.count { it.payload is RuntimeEventPayload.ApprovalRequested })

        val deny = AppServerApprovalResponseDecision.Deny(message = "not now")
        assertEquals(ApprovalSubmitResult.Accepted, engine.submitApprovalResponse(runtime, "approval-1", deny))

        client.emit(controlRequest("approval-1"))
        runCurrent()

        val answers = client.approvalInputs()
        assertEquals(2, answers.size, "the replay is re-answered once")
        answers.forEach { input ->
            val payload = input.payload as AppServerInputPayload.ApprovalResponse
            assertEquals("approval-1", payload.requestId)
            assertEquals(deny, payload.decision)
        }
        assertEquals(1, drafts.count { it.payload is RuntimeEventPayload.ApprovalRequested }, "no second approval card")
        turn.cancel()
    }

    @Test
    fun rejectedDecisionIsNotReplayedFromCache() = runTest {
        val client = ApprovalAckClient()
        val engine = engineFor(client, AppServerPermissionMode.Standard)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()

        client.emit(controlRequest("approval-1"))
        runCurrent()
        client.approvalAccepted = false
        val result = engine.submitApprovalResponse(runtime, "approval-1", AppServerApprovalResponseDecision.Allow())
        assertIs<ApprovalSubmitResult.Rejected>(result)

        client.emit(controlRequest("approval-1"))
        runCurrent()

        assertEquals(1, client.approvalInputs().size, "a rejected decision is forgotten, not re-sent")
        turn.cancel()
    }

    private fun TestScope.engineFor(client: AppServerClient, mode: AppServerPermissionMode) = AppServerTurnEngine(
        client = client,
        permissionMode = mode,
        turnIdleTimeoutMs = 60_000,
        terminalSettleQuietMs = 10,
        nowMs = { testScheduler.currentTime },
    )

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-1:conv-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hey"),
        )

        fun controlRequest(requestId: String) = AppServerInboundFrame.ControlRequest(
            requestId = requestId,
            request = buildJsonObject {
                put("subtype", "can_use_tool")
                put("tool_name", "searxng_web_search")
                put("tool_call_id", "tool-call-1")
                put("input", buildJsonObject { put("query", "iroh") })
            },
            agentId = runtime.agentId,
            conversationId = runtime.conversationId,
        )
    }

    private class ApprovalAckClient : FakeAppServerTestClient() {
        val acknowledgedInputs = mutableListOf<AppServerCommand.Input>()
        val plainInputs = mutableListOf<AppServerCommand.Input>()
        var supportsAck = true
        var approvalAccepted = true
        var approvalAckGate: CompletableDeferred<AppServerInboundFrame.InputAccepted>? = null

        fun approvalInputs() = acknowledgedInputs.filter { it.payload is AppServerInputPayload.ApprovalResponse }

        fun ackFor(command: AppServerCommand.Input, accepted: Boolean) = AppServerInboundFrame.InputAccepted(
            requestId = requireNotNull(command.requestId),
            runtime = command.runtime,
            accepted = accepted,
            disposition = if (accepted) "started" else null,
            error = if (accepted) null else "Approval request is no longer pending",
        )

        override suspend fun input(command: AppServerCommand.Input) {
            plainInputs += command
        }

        override suspend fun inputAwaitingAcceptance(
            command: AppServerCommand.Input,
        ): AppServerInboundFrame.InputAccepted {
            if (!supportsAck) throw UnsupportedOperationException("no ack")
            acknowledgedInputs += command
            if (command.payload !is AppServerInputPayload.ApprovalResponse) return ackFor(command, accepted = true)
            approvalAckGate?.let { gate ->
                approvalAckGate = null
                return gate.await()
            }
            return ackFor(command, accepted = approvalAccepted)
        }
    }
}
