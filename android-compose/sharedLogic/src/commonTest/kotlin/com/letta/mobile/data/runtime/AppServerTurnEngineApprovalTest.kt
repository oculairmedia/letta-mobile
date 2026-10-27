package com.letta.mobile.data.runtime



import app.cash.turbine.test
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineApprovalTest : AppServerTurnEngineFixture() {
    @Test
    fun unrestrictedRuntimeAutoApprovesControlRequestsWithoutEmittingApprovalCards() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            permissionMode = AppServerPermissionMode.Unrestricted,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            val userInput = assertIs<AppServerCommand.Input>(client.sentCommands.single())
            assertIs<AppServerInputPayload.CreateMessage>(userInput.payload)

            client.emit(
                AppServerInboundFrame.ControlRequest(
                    requestId = "approval-1",
                    request = buildJsonObject {
                        put("subtype", "can_use_tool")
                        put("tool_name", "searxng_web_search")
                        put("tool_call_id", "tool-call-1")
                        put("input", buildJsonObject { put("query", "iroh") })
                    },
                    agentId = runtime.agentId,
                    conversationId = runtime.conversationId,
                ),
            )
            runCurrent()

            val approvalInput = assertIs<AppServerCommand.Input>(client.sentCommands.last())
            val approval = assertIs<AppServerInputPayload.ApprovalResponse>(approvalInput.payload)
            assertEquals("approval-1", approval.requestId)
            assertIs<AppServerApprovalResponseDecision.Allow>(approval.decision)
            // The approval CARD is suppressed, but the tool-call announcement
            // must surface so the tool chip renders live (toolchip-live fix).
            val toolCall = assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)
            assertEquals("tool-call-1", toolCall.toolCallId.value)
            assertEquals("searxng_web_search", toolCall.toolName.value)
            expectNoEvents()

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            // fix(no-settle-on-clean-completion): no tool return arrived before
            // this CLEAN terminal, but a clean completion must never
            // synthesize a Failed return for a still-dangling call — the
            // real async return can legitimately still be coming from the
            // server. See AppServerTurnEngine.settleDanglingToolCalls() KDoc.
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }

    @Test
    fun engineWithoutExplicitPermissionModeAutoApprovesToolCalls() = runTest {
        // letta-mobile-h5t1g: an engine built with no explicit mode (cold start,
        // nothing cached) must approve tool calls instead of parking the turn until
        // the idle watchdog force-fails it.
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerInputPayload.CreateMessage>(assertIs<AppServerCommand.Input>(client.sentCommands.single()).payload)

            client.emit(
                AppServerInboundFrame.ControlRequest(
                    requestId = "approval-1",
                    request = buildJsonObject {
                        put("subtype", "can_use_tool")
                        put("tool_name", "searxng_web_search")
                        put("tool_call_id", "tool-call-1")
                        put("input", buildJsonObject { put("query", "iroh") })
                    },
                    agentId = runtime.agentId,
                    conversationId = runtime.conversationId,
                ),
            )
            runCurrent()

            val approvalInput = assertIs<AppServerCommand.Input>(client.sentCommands.last())
            val approval = assertIs<AppServerInputPayload.ApprovalResponse>(approvalInput.payload)
            assertEquals("approval-1", approval.requestId)
            assertIs<AppServerApprovalResponseDecision.Allow>(approval.decision)

            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun explicitStandardModeStillSurfacesApprovalInsteadOfAutoApproving() = runTest {
        // letta-mobile-h5t1g: the default changed, but an explicitly requested
        // Standard mode must still gate tool calls behind a user decision.
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            permissionMode = AppServerPermissionMode.Standard,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(1, client.sentCommands.size)

            client.emit(
                AppServerInboundFrame.ControlRequest(
                    requestId = "approval-1",
                    request = buildJsonObject {
                        put("subtype", "can_use_tool")
                        put("tool_name", "searxng_web_search")
                        put("tool_call_id", "tool-call-1")
                        put("input", buildJsonObject { put("query", "iroh") })
                    },
                    agentId = runtime.agentId,
                    conversationId = runtime.conversationId,
                ),
            )
            runCurrent()

            // No approval answered on the wire: only the original CreateMessage.
            assertEquals(1, client.sentCommands.size)
            val approvalRequested = assertIs<RuntimeEventPayload.ApprovalRequested>(awaitItem().payload)
            assertEquals("approval-1", approvalRequested.request.approvalId.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun unrestrictedRuntimeAutoApprovesStreamedApprovalRequestMessagesWithoutEmittingCards() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            permissionMode = AppServerPermissionMode.Unrestricted,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerInputPayload.CreateMessage>(assertIs<AppServerCommand.Input>(client.sentCommands.single()).payload)

            val approvalDelta = buildJsonObject {
                put("message_type", "approval_request_message")
                put("id", "approval-1")
                put("run_id", "run-1")
                put("tool_call", buildJsonObject {
                    put("tool_call_id", "tool-call-1")
                    put("name", "Skill")
                    put("arguments", "{}")
                })
            }
            client.emit(
                AppServerInboundFrame.StreamDelta(
                    runtime = runtime,
                    eventSeq = 1,
                    emittedAt = "2026-06-24T00:00:00Z",
                    idempotencyKey = "approval-evt-1",
                    delta = approvalDelta,
                ),
            )
            runCurrent()

            // letta-mobile-qygvv.13: the server already approved the tool under
            // Unrestricted; the delta is informational, so no approval_response is sent.
            assertTrue(
                client.sentCommands.none { (it as? AppServerCommand.Input)?.payload is AppServerInputPayload.ApprovalResponse },
                "no redundant approval_response for an unrestricted approval_request_message",
            )
            // Approval card suppressed; tool-call announcement surfaces so the
            // Skill tool chip renders live (toolchip-live fix).
            val toolCall = assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)
            assertEquals("tool-call-1", toolCall.toolCallId.value)
            assertEquals("Skill", toolCall.toolName.value)
            expectNoEvents()

            client.emit(streamDelta(messageType = "tool_return_message", runId = "run-1", toolCallId = "tool-call-1"))
            assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }

    @Test
    fun surfacedRuntimeUserInputApprovalPausesIdleWatchdog() = runTest {
        // letta-mobile-vilsn.6: an AskUserQuestion / ExitPlanMode approval that is
        // NOT auto-approved parks the turn awaiting the user's answer. The idle
        // watchdog must NOT fire while that approval is outstanding — an unanswered
        // question must not synthesize a Failed idle-timeout lifecycle.
        //
        // letta-mobile-465hq: the engine's clock is INJECTED, so the watchdog reads
        // the same virtual timeline `delay()` advances. No Dispatchers.Default
        // escape, no real sleeps — the test is load-insensitive.
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            permissionMode = AppServerPermissionMode.Unrestricted,
            turnIdleTimeoutMs = 300,
            nowMs = { testScheduler.currentTime },
        )
        val payloads = MutableStateFlow<List<RuntimeEventPayload>>(emptyList())
        val job = launch {
            engine.runTurn(command).collect { draft -> payloads.update { it + draft.payload } }
        }

        // An Input command is sent only after the collector subscribes, so
        // draining the current virtual instant guarantees the ControlRequest
        // below is observed.
        runCurrent()
        assertTrue(
            client.sentCommands.any { it is AppServerCommand.Input },
            "the turn must have subscribed and sent its input",
        )

        client.emit(
            AppServerInboundFrame.ControlRequest(
                requestId = "approval-userinput-1",
                request = buildJsonObject {
                    put("subtype", "can_use_tool")
                    put("tool_name", "AskUserQuestion")
                    put("tool_call_id", "tool-call-1")
                    put("input", buildJsonObject { put("question", "pick one") })
                },
                agentId = runtime.agentId,
                conversationId = runtime.conversationId,
            ),
        )
        // The user-input tool is surfaced as an approval (NOT auto-approved).
        runCurrent()
        assertTrue(
            payloads.value.any { it is RuntimeEventPayload.ApprovalRequested },
            "the user-input tool must be surfaced as an approval",
        )

        // Idle far longer than the watchdog window while the approval is
        // outstanding: the watchdog is paused and MUST NOT fail the turn.
        advanceTimeBy(300L * 4)
        runCurrent()
        assertTrue(
            payloads.value.none {
                it is RuntimeEventPayload.RunLifecycleChanged && it.status == RuntimeRunStatus.Failed
            },
            "idle watchdog must not fail a turn parked on a user-input approval",
        )
        // The turn is still parked (no terminal), so the collector is active.
        assertTrue(job.isActive, "turn must stay pending while awaiting the user's answer")
        job.cancel()
    }

    @Test
    fun normalIdleTurnStillFailsViaWatchdogWhenNoUserInputApproval() = runTest {
        // letta-mobile-vilsn.6: the pause is scoped to outstanding user-input
        // approvals ONLY — a genuinely idle/stuck turn with no such approval must
        // still be force-failed by the idle watchdog.
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            turnIdleTimeoutMs = 150,
            nowMs = { testScheduler.currentTime },
        )
        val payloads = MutableStateFlow<List<RuntimeEventPayload>>(emptyList())
        val job = launch {
            engine.runTurn(command).collect { draft -> payloads.update { it + draft.payload } }
        }

        runCurrent()
        assertTrue(
            client.sentCommands.any { it is AppServerCommand.Input },
            "the turn must have subscribed and sent its input",
        )

        // No further frames ever arrive: the watchdog must synthesize a Failed
        // idle-timeout lifecycle so the shared lock is released.
        advanceTimeBy(150L * 4)
        runCurrent()
        assertTrue(
            payloads.value.any {
                it is RuntimeEventPayload.RunLifecycleChanged && it.status == RuntimeRunStatus.Failed
            },
            "idle watchdog must still fail a genuinely idle turn",
        )
        job.cancel()
    }

    @Test
    fun unrelatedInboundFrameDoesNotLiftUserInputWatchdogPause() = runTest {
        // letta-mobile-vilsn.6 (CodeRabbit Major / Codex P1): while a surfaced
        // user-input approval gate is outstanding the idle watchdog is paused — and
        // a side-channel status frame (UpdateDeviceStatus) that merely passes
        // matches(scope) must NOT lift that pause. Under the old blanket-boolean
        // design this first unrelated frame cleared the pause and the watchdog then
        // force-failed a turn that was still legitimately awaiting the user's
        // answer. The turn must NOT emit a Failed idle-timeout within the window.
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            permissionMode = AppServerPermissionMode.Unrestricted,
            turnIdleTimeoutMs = 300,
            nowMs = { testScheduler.currentTime },
        )
        val payloads = MutableStateFlow<List<RuntimeEventPayload>>(emptyList())
        val job = launch {
            engine.runTurn(command).collect { draft -> payloads.update { it + draft.payload } }
        }

        runCurrent()
        assertTrue(
            client.sentCommands.any { it is AppServerCommand.Input },
            "the turn must have subscribed and sent its input",
        )

        client.emit(
            AppServerInboundFrame.ControlRequest(
                requestId = "approval-userinput-1",
                request = buildJsonObject {
                    put("subtype", "can_use_tool")
                    put("tool_name", "AskUserQuestion")
                    put("tool_call_id", "tool-call-1")
                    put("input", buildJsonObject { put("question", "pick one") })
                },
                agentId = runtime.agentId,
                conversationId = runtime.conversationId,
            ),
        )
        runCurrent()
        assertTrue(
            payloads.value.any { it is RuntimeEventPayload.ApprovalRequested },
            "the user-input tool must be surfaced as an approval",
        )

        // A side-channel status frame arrives, scoped to the SAME runtime so it
        // passes matches(scope). It must NOT resolve the outstanding gate.
        client.emit(
            AppServerInboundFrame.UpdateDeviceStatus(
                runtime = runtime,
                eventSeq = 2,
                emittedAt = "2026-06-24T00:00:01Z",
                idempotencyKey = "device-status-1",
                deviceStatus = buildJsonObject { put("battery", 50) },
            ),
        )
        runCurrent()

        // Idle far past the watchdog window: the pause must hold despite the
        // unrelated frame, so no Failed idle-timeout is synthesized.
        advanceTimeBy(300L * 4)
        runCurrent()
        assertTrue(
            payloads.value.none {
                it is RuntimeEventPayload.RunLifecycleChanged && it.status == RuntimeRunStatus.Failed
            },
            "an unrelated status frame must not lift the user-input watchdog pause",
        )
        assertTrue(job.isActive, "turn must stay pending while awaiting the user's answer")
        job.cancel()
    }

    @Test
    fun idleWatchdogResumesAndFailsAfterUserInputGateResolvesViaToolReturn() = runTest {
        // letta-mobile-vilsn.6: once the outstanding user-input gate is genuinely
        // resolved (its tool_return arrives — the user answered and the tool ran),
        // the pause is lifted and a subsequent genuine idle DOES force-fail via the
        // watchdog. The pause is per-gate and not sticky, so activeTurn is never
        // left wedged after a real answer.
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            permissionMode = AppServerPermissionMode.Unrestricted,
            turnIdleTimeoutMs = 150,
            nowMs = { testScheduler.currentTime },
        )
        val payloads = MutableStateFlow<List<RuntimeEventPayload>>(emptyList())
        val job = launch {
            engine.runTurn(command).collect { draft -> payloads.update { it + draft.payload } }
        }

        runCurrent()
        assertTrue(
            client.sentCommands.any { it is AppServerCommand.Input },
            "the turn must have subscribed and sent its input",
        )

        client.emit(
            AppServerInboundFrame.ControlRequest(
                requestId = "approval-userinput-1",
                request = buildJsonObject {
                    put("subtype", "can_use_tool")
                    put("tool_name", "AskUserQuestion")
                    put("tool_call_id", "tool-call-1")
                    put("input", buildJsonObject { put("question", "pick one") })
                },
                agentId = runtime.agentId,
                conversationId = runtime.conversationId,
            ),
        )
        runCurrent()
        assertTrue(
            payloads.value.any { it is RuntimeEventPayload.ApprovalRequested },
            "the user-input tool must be surfaced as an approval",
        )

        // The gate for tool-call-1 resolves via its tool_return.
        client.emit(streamDelta(messageType = "client_tool_end", runId = "run-1", toolCallId = "tool-call-1"))
        runCurrent()
        assertTrue(
            payloads.value.any { it is RuntimeEventPayload.ToolReturnObserved },
            "the tool_return must resolve the outstanding gate",
        )

        // Now genuinely idle with the gate cleared: the watchdog must resume and
        // force-fail the turn so the shared lock is released.
        advanceTimeBy(150L * 4)
        runCurrent()
        assertTrue(
            payloads.value.any {
                it is RuntimeEventPayload.RunLifecycleChanged && it.status == RuntimeRunStatus.Failed
            },
            "idle watchdog must resume and fail once the user-input gate is resolved",
        )
        job.cancel()
    }
}

