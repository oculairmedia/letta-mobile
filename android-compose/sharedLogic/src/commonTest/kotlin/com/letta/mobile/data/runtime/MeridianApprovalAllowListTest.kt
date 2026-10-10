package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerCommand
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-jna0o.7: an allow-listed `meridian` CLI call raises no approval card in any
 * permission mode (the external tool it replaces never asked), while every other shell command and
 * every user-input tool still does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeridianApprovalAllowListTest {
    @Test
    fun meridianCallIsAnsweredWithoutACardUnderStandardAndStrict() = runTest {
        listOf(AppServerPermissionMode.Standard, AppServerPermissionMode.Strict).forEach { mode ->
            val turn = startTurn(mode)
            turn.client.emit(bash(MERIDIAN_COMPOSE))
            runCurrent()

            assertEquals(0, turn.drafts.approvalCards(), "no card under $mode")
            assertEquals(listOf("perm-1"), turn.client.approvalInputs().map { it.approvalRequestId() }, "auto-allowed under $mode")
            assertTrue(turn.drafts.any { it.payload is RuntimeEventPayload.ToolCallObserved }, "the tool call still shows")
            turn.job.cancel()
        }
    }

    @Test
    fun otherShellCommandsAndChainedMeridianCallsStillAsk() = runTest {
        listOf("ls -la", "meridian canvas list; rm -rf ~", "meridian rest get /v1/agents").forEach { command ->
            val turn = startTurn(AppServerPermissionMode.Standard)
            turn.client.emit(bash(command))
            runCurrent()

            assertEquals(1, turn.drafts.approvalCards(), "'$command' must ask")
            assertTrue(turn.client.approvalInputs().isEmpty())
            turn.job.cancel()
        }
    }

    @Test
    fun userInputToolsStillAskEvenUnderUnrestricted() = runTest {
        val turn = startTurn(AppServerPermissionMode.Unrestricted)
        turn.client.emit(TestApprovalTool.AskUserQuestion.controlRequest())
        runCurrent()

        assertEquals(1, turn.drafts.approvalCards())
        turn.job.cancel()
    }

    @Test
    fun streamedMeridianApprovalUnderStandardSendsNoReplyAndShowsNoCard() = runTest {
        val turn = startTurn(AppServerPermissionMode.Standard)
        turn.client.emit(streamedBash(MERIDIAN_COMPOSE))
        runCurrent()

        assertEquals(0, turn.drafts.approvalCards())
        assertTrue(turn.client.approvalInputs().isEmpty(), "the streamed message is informational")
        assertEquals(null, turn.engine.userInputApprovalId("tool-call-1"), "no gate parks the turn")
        turn.job.cancel()
    }

    @Test
    fun unleasedMeridianCallIsAutoAllowedUnderStandardButOtherBashIsLeftPending() = runTest {
        val client = TurnEngineTestRecordingClient().apply { approvalAcks = { started(it) } }
        val engine = engine(client, AppServerPermissionMode.Standard)
        engine.runTurn(autoFinishCommand).collect()

        val meridian = engine.answerUnleasedControlRequest(bash(MERIDIAN_COMPOSE, requestId = "perm-m"))
        val other = engine.answerUnleasedControlRequest(bash("curl https://example.com", requestId = "perm-c"))

        assertEquals(UnleasedApprovalOutcome.AutoAllowed, meridian)
        assertEquals(UnleasedApprovalOutcome.LeftPending, other)
        assertEquals(1, client.inputs.count { it.payload is AppServerInputPayload.ApprovalResponse })
    }

    private class RunningTurn(
        val client: TurnEngineTestAckingClient,
        val engine: AppServerTurnEngine,
        val drafts: List<RuntimeEventDraft>,
        val job: Job,
    )

    private fun TestScope.startTurn(mode: AppServerPermissionMode): RunningTurn {
        val client = TurnEngineTestAckingClient(frames, InputAckFixture.Started)
        val engine = engine(client, mode)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val job = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()
        return RunningTurn(client, engine, drafts, job)
    }

    private fun TestScope.engine(client: com.letta.mobile.data.transport.appserver.AppServerClient, mode: AppServerPermissionMode) =
        AppServerTurnEngine(
            client = client,
            permissionMode = mode,
            turnIdleTimeoutMs = 600_000,
            terminalSettleQuietMs = 10,
            nowMs = { testScheduler.currentTime },
        )

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val frames = TurnEngineTestFrames(runtime)

        const val MERIDIAN_COMPOSE = "meridian canvas compose <<'JSON'\n{\"items\": [{\"kind\": \"NOTE\", \"markdown\": \"hi\"}]}\nJSON"

        /** A `can_use_tool` for a Bash call running [command]. */
        fun bash(command: String, requestId: String = "perm-1") = AppServerInboundFrame.ControlRequest(
            requestId = requestId,
            request = buildJsonObject {
                put("subtype", "can_use_tool")
                put("tool_name", "Bash")
                put("tool_call_id", "call-$requestId")
                put("input", buildJsonObject { put("command", command) })
            },
            agentId = runtime.agentId,
            conversationId = runtime.conversationId,
        )

        /** The streamed `approval_request_message` for the same kind of call. */
        fun streamedBash(command: String): AppServerInboundFrame.StreamDelta {
            val base = frames.approvalRequestMessage(toolName = "Bash")
            val arguments = buildJsonObject { put("command", command) }.toString()
            return base.copy(
                delta = buildJsonObject {
                    put("message_type", "approval_request_message")
                    put("id", "letta-msg-meridian")
                    put("tool_call", buildJsonObject {
                        put("tool_call_id", "tool-call-1")
                        put("name", "Bash")
                        put("arguments", arguments)
                    })
                },
            )
        }

        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-1:conv-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hey"),
        )

        val autoFinishCommand = command.copy(input = TurnInput.UserMessage(localMessageId = AUTO_FINISH_MESSAGE_ID, text = "hi"))

        fun started(input: AppServerCommand.Input) = AppServerInboundFrame.InputAccepted(
            requestId = requireNotNull(input.requestId),
            runtime = runtime,
            accepted = true,
            disposition = "started",
        )
    }
}
