package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-jvctq: `autoAllowApprovals`. The default (true) keeps every existing caller's
 * behaviour: under Unrestricted the engine answers a `can_use_tool` itself. False (the swarm CLI)
 * answers nothing, leased or not: the request surfaces as an approval card for the person.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineAutoAllowFlagTest {
    @Test
    fun defaultStillAutoAllowsUnderUnrestricted() = runTest {
        val (client, drafts) = runTurnWithApproval(autoAllowApprovals = null)

        assertEquals(listOf(TEST_APPROVAL_REQUEST_ID), client.approvalInputs().map { it.approvalRequestId() })
        assertEquals(0, drafts.approvalCards())
    }

    @Test
    fun explicitTrueIsTheDefault() = runTest {
        val (client, _) = runTurnWithApproval(autoAllowApprovals = true)

        assertEquals(listOf(TEST_APPROVAL_REQUEST_ID), client.approvalInputs().map { it.approvalRequestId() })
    }

    @Test
    fun falseNeverAnswersAndSurfacesTheApproval() = runTest {
        val (client, drafts) = runTurnWithApproval(autoAllowApprovals = false)

        assertTrue(client.approvalInputs().isEmpty(), "no approval_response leaves this client")
        assertTrue(client.plainInputs.none { it.approvalRequestId() != null })
        assertEquals(1, drafts.approvalCards(), "the request waits for the person")
    }

    @Test
    fun falseLeavesAnUnleasedApprovalPendingToo() = runTest {
        val client = TurnEngineTestAckingClient(frames, InputAckFixture.Started)
        val engine = engineFor(client, autoAllowApprovals = false)
        val job = launch { engine.runTurn(command).collect {} }
        runCurrent()
        job.cancel()
        runCurrent()

        val outcome = engine.answerUnleasedControlRequest(frames.approvalControlRequest())

        assertEquals(UnleasedApprovalOutcome.NotOwned, outcome)
        assertTrue(client.approvalInputs().isEmpty())
    }

    private fun TestScope.runTurnWithApproval(
        autoAllowApprovals: Boolean?,
    ): Pair<TurnEngineTestAckingClient, List<RuntimeEventDraft>> {
        val client = TurnEngineTestAckingClient(frames, InputAckFixture.Started)
        val engine = engineFor(client, autoAllowApprovals)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val job = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()
        client.emit(frames.approvalControlRequest())
        runCurrent()
        job.cancel()
        return client to drafts
    }

    private fun TestScope.engineFor(client: TurnEngineTestAckingClient, autoAllowApprovals: Boolean?): AppServerTurnEngine =
        if (autoAllowApprovals == null) {
            AppServerTurnEngine(
                client = client,
                permissionMode = AppServerPermissionMode.Unrestricted,
                turnIdleTimeoutMs = 60_000,
                terminalSettleQuietMs = 10,
                nowMs = { testScheduler.currentTime },
            )
        } else {
            AppServerTurnEngine(
                client = client,
                permissionMode = AppServerPermissionMode.Unrestricted,
                turnIdleTimeoutMs = 60_000,
                terminalSettleQuietMs = 10,
                nowMs = { testScheduler.currentTime },
                autoAllowApprovals = autoAllowApprovals,
            )
        }

    private companion object {
        val frames = TurnEngineTestFrames(AppServerRuntimeScope("agent-1", "conv-1"))
        val command = TurnCommand(
            backendId = BackendId("swarm"),
            runtimeId = RuntimeId("swarm:conv-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hey"),
        )
    }
}
