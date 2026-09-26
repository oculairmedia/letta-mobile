package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-qygvv.10: an unleased auto-allow goes through the same [ApprovalResponseSender] as a
 * leased one: the decision is cached by approval id (a duplicate sends no second frame, a replay
 * is re-answered from the cache), `input_accepted` is awaited, and a rejection is surfaced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnleasedApprovalSenderTest {
    @Test
    fun unleasedAnswerAwaitsInputAccepted() = runTest {
        val client = TurnEngineTestRecordingClient()
        val ack = CompletableDeferred<AppServerInboundFrame.InputAccepted>()
        client.approvalAcks = { ack.await() }
        val engine = ownedEngine(client)

        val outcome = async { engine.answerUnleasedControlRequest(TestApprovalTool.Bash.controlRequest()) }
        runCurrent()

        val sent = client.approvalInputs().single()
        assertNotNull(sent.requestId, "the unleased answer carries a request_id like a leased one")
        assertFalse(outcome.isCompleted, "the answer waits for input_accepted")
        ack.complete(started(sent))
        runCurrent()
        assertEquals(UnleasedApprovalOutcome.AutoAllowed, outcome.await())
    }

    @Test
    fun duplicateUnleasedAnswerSendsNoSecondFrame() = runTest {
        val client = TurnEngineTestRecordingClient().apply { approvalAcks = { started(it) } }
        val engine = ownedEngine(client)
        val frame = TestApprovalTool.Bash.controlRequest()

        assertEquals(UnleasedApprovalOutcome.AutoAllowed, engine.answerUnleasedControlRequest(frame))
        assertEquals(UnleasedApprovalOutcome.AlreadyDecided, engine.answerUnleasedControlRequest(frame))
        val otherGeneration = engine.answerUnleasedControlRequest(frame, connectionGeneration = 1L)
        assertEquals(UnleasedApprovalOutcome.AlreadyDecided, otherGeneration)

        assertEquals(1, client.approvalResponses.size, "one decision, one frame")
        assertNotNull(engine.cachedApprovalDecisionFor(frame), "the decision is cached by approval id")
    }

    @Test
    fun rejectedUnleasedAnswerIsSurfacedAndForgotten() = runTest {
        val client = TurnEngineTestRecordingClient()
        client.approvalAcks = { rejected(it) }
        val engine = ownedEngine(client)
        val frame = TestApprovalTool.Bash.controlRequest()

        assertEquals(UnleasedApprovalOutcome.Rejected, engine.answerUnleasedControlRequest(frame))
        assertNull(engine.cachedApprovalDecisionFor(frame), "a rejected decision is not replayed")

        client.approvalAcks = { started(it) }
        assertEquals(UnleasedApprovalOutcome.AutoAllowed, engine.answerUnleasedControlRequest(frame), "the claim was handed back")
    }

    @Test
    fun serverReplayIsReansweredFromTheCache() = runTest {
        val client = TurnEngineTestRecordingClient().apply { approvalAcks = { started(it) } }
        val engine = routedEngine(client)
        val frame = TestApprovalTool.Bash.controlRequest()
        assertEquals(UnleasedApprovalOutcome.AutoAllowed, engine.answerUnleasedControlRequest(frame))

        client.emit(frame)
        runCurrent()

        val answers = client.approvalResponses
        assertEquals(2, answers.size, "the replay is re-answered once")
        assertTrue(answers.all { it.requestId == "perm-1" })
        assertTrue(answers.all { it.decision is AppServerApprovalResponseDecision.Allow })
    }

    @Test
    fun frameSeenWhileItsAnswerAwaitsTheAckSendsNoSecondFrame() = runTest {
        val client = TurnEngineTestRecordingClient()
        val ack = CompletableDeferred<AppServerInboundFrame.InputAccepted>()
        val engine = routedEngine(client)
        client.approvalAcks = { ack.await() }
        val frame = TestApprovalTool.Bash.controlRequest()

        // The unleased answerer won the race: its decision is cached, the ack not yet in.
        val outcome = async { engine.answerUnleasedControlRequest(frame) }
        runCurrent()
        client.emit(frame)
        runCurrent()
        assertEquals(1, client.approvalResponses.size, "the router must not re-send an in-flight decision")

        ack.complete(started(client.approvalInputs().single()))
        assertEquals(UnleasedApprovalOutcome.AutoAllowed, outcome.await())
        assertEquals(1, client.approvalResponses.size)
    }

    private fun TurnEngineTestRecordingClient.approvalInputs(): List<AppServerCommand.Input> =
        inputs.filter { it.payload is AppServerInputPayload.ApprovalResponse }

    /** An engine that ran one turn on `agent-1/conv-1`, so unleased approvals there are its own. */
    private suspend fun TestScope.ownedEngine(client: TurnEngineTestRecordingClient): AppServerTurnEngine =
        engine(client).also { it.runTurn(autoFinishCommand).collect() }

    /** [ownedEngine] behind a controller-style router that re-answers replays from the cache. */
    private suspend fun TestScope.routedEngine(client: TurnEngineTestRecordingClient): AppServerTurnEngine {
        val router = AppServerRuntimeEventRouter()
        router.attach(backgroundScope, client.events)
        val engine = engine(client, router)
        engine.answerApprovalReplaysFrom(router, backgroundScope)
        engine.runTurn(autoFinishCommand).collect()
        return engine
    }

    private fun TestScope.engine(client: TurnEngineTestRecordingClient, router: AppServerRuntimeEventRouter? = null) =
        AppServerTurnEngine(
            client = client,
            permissionMode = AppServerPermissionMode.Unrestricted,
            turnIdleTimeoutMs = 600_000,
            eventRouter = router,
            nowMs = { testScheduler.currentTime },
        )

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val autoFinishCommand = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = AUTO_FINISH_MESSAGE_ID, text = "hi"),
        )

        fun started(command: AppServerCommand.Input) = ack(command, accepted = true)

        fun rejected(command: AppServerCommand.Input) = ack(command, accepted = false)

        private fun ack(command: AppServerCommand.Input, accepted: Boolean) = AppServerInboundFrame.InputAccepted(
            requestId = command.requestId.orEmpty(),
            runtime = runtime,
            accepted = accepted,
            disposition = if (accepted) "started" else null,
            error = if (accepted) null else ApprovalResponseSender.APPROVAL_NO_LONGER_PENDING,
        )
    }
}
