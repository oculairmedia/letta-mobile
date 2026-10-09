package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.flow.first
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
            onInForce = { scope, mode -> reported += scope to mode; null },
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

    @Test
    fun aChoiceMadeWhileRuntimeStartIsInFlightIsSentOnceItReturns() = runTest {
        val startGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val changes = mutableListOf<AppServerPermissionMode?>()
        val client = object : TurnEngineTestStreamClient() {
            override suspend fun runtimeStart(command: com.letta.mobile.data.transport.appserver.AppServerCommand.RuntimeStart):
                AppServerInboundFrame.RuntimeStartResponse {
                startGate.await()
                return super.runtimeStart(command)
            }

            override suspend fun changeDeviceState(command: com.letta.mobile.data.transport.appserver.AppServerCommand.ChangeDeviceState) {
                changes += command.payload.mode
                command.payload.mode?.let { emit(DeviceStatusFixture.inMode(it).frame(runtime)) }
            }
        }
        val modes = PermissionModeSettings(com.letta.mobile.data.chat.runtime.MapSettingsStore()).modes
        val engine = AppServerTurnEngine(
            client = client,
            permissionModeProvider = { modes.modeFor(AppServerRuntimeScope(it.agentId.value, it.conversationId.value)) },
            onPermissionModeInForce = { scope, mode -> modes.observed(scope, mode) },
            turnIdleTimeoutMs = 600_000,
            nowMs = { testScheduler.currentTime },
        )
        collect(engine) // runtime_start is now waiting on the gate, carrying Unrestricted

        // The person picks Strict while it is in flight: no runtime yet, so nothing is sent.
        modes.change(runtime, AppServerPermissionMode.Strict) { engine.setPermissionMode(runtime, it) }
        assertEquals(emptyList(), changes)

        startGate.complete(Unit)
        runCurrent()

        assertEquals(listOf<AppServerPermissionMode?>(AppServerPermissionMode.Strict), changes, "sent as a change on the started runtime")
        assertEquals(PermissionModeState(AppServerPermissionMode.Strict), modes.observe(runtime).first())
    }

    @Test
    fun aStreamedOnlyApprovalIsGatedUnderStandardButNotUnderAcceptEdits() = runTest {
        val accept = TurnEngineTestStreamClient()
        val acceptEngine = engine(accept, modeProvider = { AppServerPermissionMode.AcceptEdits })
        collect(acceptEngine)
        accept.emit(frames.approvalRequestMessage("Edit"))
        runCurrent()
        assertEquals(emptyMap(), acceptEngine.pendingApprovalDetails.value, "the server decides edits itself there")

        val standard = TurnEngineTestStreamClient()
        val standardEngine = engine(standard, modeProvider = { AppServerPermissionMode.Standard })
        collect(standardEngine)
        standard.emit(frames.approvalRequestMessage("Edit"))
        runCurrent()
        assertTrue("tool-call-1" in standardEngine.pendingApprovalDetails.value)
    }

    @Test
    fun aStreamedApprovalReplayedAfterTheToolReturnedIsNotParkedAgain() = runTest {
        val client = TurnEngineTestStreamClient()
        val engine = engine(client, modeProvider = { AppServerPermissionMode.Standard })
        collect(engine)
        client.emit(frames.approvalRequestMessage("Bash"))
        runCurrent()
        client.emit(frames.streamDelta("tool_return_message").let { toolReturn(it) })
        runCurrent()
        assertEquals(emptyMap(), engine.pendingApprovalDetails.value)

        client.emit(frames.approvalRequestMessage("Bash")) // resync / replay of the old message
        runCurrent()

        assertEquals(emptyMap(), engine.pendingApprovalDetails.value, "a finished call is not parked, and gets no gate that pauses the watchdog")
        assertFalse(engine.pendingApprovalDetails.value.isNotEmpty())
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
        onInForce: (AppServerRuntimeScope, AppServerPermissionMode) -> AppServerPermissionMode? = { _, _ -> null },
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
