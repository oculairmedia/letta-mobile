package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-bzvro.13: the permission mode is read per approval (not snapshotted per turn), the
 * mode a runtime is in is reported, and a surfaced approval of any tool parks the turn without
 * letting it hang.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEnginePermissionModeTest {
    private val frames = TurnEngineTestFrames(runtime)

    @Test
    fun aModeTightenedMidTurnStopsAutoApprovingTheNextApproval() = runTest {
        val client = TurnEngineTestStreamClient()
        var mode = AppServerPermissionMode.Unrestricted
        val engine = engine(client, modeProvider = { mode })
        val payloads = collect(engine)

        client.emit(bashRequest("perm-a", "call-a"))
        runCurrent()
        assertEquals(0, payloads.value.count { it is RuntimeEventPayload.ApprovalRequested }, "Unrestricted auto-approves")

        mode = AppServerPermissionMode.Strict // changed from the chip while the turn runs
        client.emit(bashRequest("perm-b", "call-b"))
        runCurrent()

        val surfaced = payloads.value.filterIsInstance<RuntimeEventPayload.ApprovalRequested>().single()
        assertEquals("call-b", surfaced.request.callId.value, "the stricter mode governs the very next approval")
        assertTrue("call-b" in engine.pendingApprovalDetails.value)
    }

    @Test
    fun theModeARuntimeIsInIsReportedWhenItStartsAndWhenTheServerSaysSo() = runTest {
        val client = TurnEngineTestStreamClient()
        val reported = mutableListOf<Pair<AppServerRuntimeScope, AppServerPermissionMode>>()
        val engine = engine(
            client,
            modeProvider = { AppServerPermissionMode.Strict },
            onInForce = { scope, mode -> reported += scope to mode },
        )
        collect(engine)
        assertEquals(listOf(runtime to AppServerPermissionMode.Strict), reported, "runtime_start carried Strict")

        client.emit(DeviceStatusFixture.inMode(AppServerPermissionMode.Unrestricted).frame(runtime))
        runCurrent()

        assertEquals(runtime to AppServerPermissionMode.Unrestricted, reported.last(), "the device status is the server's word")
    }

    @Test
    fun aParkedBashApprovalPausesTheWatchdogAndAnAbortReleasesIt() = runTest {
        val client = object : TurnEngineTestStreamClient() {
            override suspend fun abort(command: com.letta.mobile.data.transport.appserver.AppServerCommand.AbortMessage) =
                AppServerInboundFrame.AbortMessageResponse(
                    requestId = command.requestId ?: "",
                    runtime = command.runtime,
                    success = true,
                    aborted = true,
                )
        }
        val engine = engine(client, modeProvider = { AppServerPermissionMode.Standard }, idleMs = 300)
        val payloads = collect(engine)
        client.emit(bashRequest("perm-a", "call-a"))
        runCurrent()
        assertTrue("call-a" in engine.pendingApprovalDetails.value, "a Bash approval is parked on the person")

        advanceTimeBy(300L * 4)
        runCurrent()
        assertFalse(payloads.value.any { it.failedLifecycle() }, "the watchdog stays paused for the parked approval")

        // The abort is confirmed: nobody can answer the approval any more, so it no longer holds the turn.
        engine.abort(runtime, runId = null)
        assertEquals(emptyMap(), engine.pendingApprovalDetails.value)
        advanceTimeBy(300L * 2)
        runCurrent()
        assertTrue(payloads.value.any { it.failedLifecycle() }, "an aborted turn cannot stay parked forever")
    }

    @Test
    fun aStreamedOnlyApprovalOfAnyToolGetsAGateAndACard() = runTest {
        val client = TurnEngineTestStreamClient()
        val engine = engine(client, modeProvider = { AppServerPermissionMode.Standard })
        collect(engine)

        client.emit(frames.approvalRequestMessage("Bash"))
        runCurrent()

        val parked = engine.pendingApprovalDetails.value.getValue("tool-call-1")
        assertEquals("Bash", parked.toolName)
        assertEquals(parked.approvalId, engine.userInputApprovalId("tool-call-1"), "answerable against the streamed request id")

        client.emit(frames.streamDelta("tool_return_message").let { toolReturn(it) })
        runCurrent()
        assertEquals(emptyMap(), engine.pendingApprovalDetails.value, "the tool returning resolves it")
    }

    @Test
    fun aControlRequestKeepsItsRealIdWhenTheStreamedMessageForTheSameCallFollows() = runTest {
        val client = TurnEngineTestStreamClient()
        val engine = engine(client, modeProvider = { AppServerPermissionMode.Standard })
        collect(engine)

        client.emit(bashRequest("perm-real", "tool-call-1"))
        runCurrent()
        client.emit(frames.approvalRequestMessage("Bash"))
        runCurrent()

        assertEquals("perm-real", engine.userInputApprovalId("tool-call-1"))
        assertEquals("perm-real", engine.pendingApprovalDetails.value.getValue("tool-call-1").approvalId)
    }

    private fun toolReturn(base: AppServerInboundFrame.StreamDelta) = base.copy(
        delta = buildJsonObject {
            put("message_type", "tool_return_message")
            put("run_id", "run-1")
            put("tool_call_id", "tool-call-1")
        },
    )

    private fun RuntimeEventPayload.failedLifecycle() =
        this is RuntimeEventPayload.RunLifecycleChanged && status == RuntimeRunStatus.Failed

    private fun bashRequest(requestId: String, callId: String) = AppServerInboundFrame.ControlRequest(
        requestId = requestId,
        request = buildJsonObject {
            put("subtype", "can_use_tool")
            put("tool_name", "Bash")
            put("tool_call_id", callId)
            put("input", buildJsonObject { put("command", "ls") })
        },
        agentId = runtime.agentId,
        conversationId = runtime.conversationId,
    )

    private fun TestScope.engine(
        client: TurnEngineTestStreamClient,
        modeProvider: (TurnCommand) -> AppServerPermissionMode,
        idleMs: Long = 600_000,
        onInForce: (AppServerRuntimeScope, AppServerPermissionMode) -> Unit = { _, _ -> },
    ) = AppServerTurnEngine(
        client = client,
        permissionModeProvider = modeProvider,
        onPermissionModeInForce = onInForce,
        turnIdleTimeoutMs = idleMs,
        nowMs = { testScheduler.currentTime },
    )

    /** Starts [engine]'s turn and returns everything it emits, once the runtime is started. */
    private fun TestScope.collect(engine: AppServerTurnEngine): MutableStateFlow<List<RuntimeEventPayload>> {
        val payloads = MutableStateFlow<List<RuntimeEventPayload>>(emptyList())
        backgroundScope.launch { engine.runTurn(command).collect { draft -> payloads.update { it + draft.payload } } }
        runCurrent()
        return payloads
    }

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
        )
    }
}
