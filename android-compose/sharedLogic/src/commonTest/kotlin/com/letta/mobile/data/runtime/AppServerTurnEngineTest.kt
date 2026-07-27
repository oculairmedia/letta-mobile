package com.letta.mobile.data.runtime

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalDecision
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalScope
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineTest {
    @Test
    fun contextRecoveryRunsBeforeRuntimeStartForUserMessages() = runTest {
        val order = mutableListOf<String>()
        val client = FakeAppServerClient(onRuntimeStart = { order += "runtime_start" }, onInput = { order += "input" })
        val engine = AppServerTurnEngine(
            client = client,
            turnContextRecovery = TurnContextRecovery { agentId, conversationId ->
                assertEquals("agent-1", agentId)
                assertEquals("conv-1", conversationId)
                order += "recovery"
                listOf("empty-1")
            },
        )

        engine.runTurn(command).test {
            awaitItem()
            assertEquals(listOf("recovery", "runtime_start", "input"), order)
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            awaitItem()
            awaitItem()
            awaitComplete()
        }
    }

    @Test
    fun contextRecoverySkipsApprovalResponses() = runTest {
        var recoveryCalls = 0
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            turnContextRecovery = TurnContextRecovery { _, _ ->
                recoveryCalls += 1
                emptyList()
            },
        )
        val approvalCommand = command.copy(
            input = TurnInput.ToolApprovalResponse(
                ToolApprovalDecision(
                    approvalId = ToolApprovalId("approval-1"),
                    callId = ToolCallId("call-1"),
                    decision = ToolApprovalDecisionValue.Approved,
                    scope = ToolApprovalScope.Once,
                ),
            ),
        )

        engine.runTurn(approvalCommand).test {
            awaitItem()
            assertEquals(0, recoveryCalls)
            assertIs<AppServerInputPayload.ApprovalResponse>(
                assertIs<AppServerCommand.Input>(client.sentCommands.single()).payload,
            )
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            awaitItem()
            awaitItem()
            awaitComplete()
        }
    }

    @Test
    fun runTurnStartsRuntimeSendsInputAndCompletesOnStopReason() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "runtime-start-1" },
        )

        engine.runTurn(command).test {
            val started = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Started, started.status)

            val input = assertIs<AppServerCommand.Input>(client.sentCommands.single())
            assertEquals(runtime, input.runtime)
            val payload = assertIs<com.letta.mobile.data.transport.appserver.AppServerInputPayload.CreateMessage>(input.payload)
            assertEquals("local-1", payload.messages.single().clientMessageId)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))

            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }

        assertEquals("runtime-start-1", client.runtimeStartCommands.single().requestId)
    }

    @Test
    fun runTurnCompletesOnUsageAfterPostToolAssistantText() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-1"))
            assertEquals("assistant_message", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            runCurrent()
            expectNoEvents()

            client.emit(streamDelta(messageType = "client_tool_end", runId = "run-1"))
            assertIs<RuntimeEventPayload.ToolReturnObserved>(awaitItem().payload)

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-2"))
            assertEquals("assistant_message", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)

            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-2"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            assertEquals("usage_statistics", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }


    @Test
    fun multiRoundToolTurnContinuesAfterPostToolUsageInterRoundTail() = runTest {
        // letta-mobile-c4igq.6: a multi-step agentic turn emits a usage_statistics
        // frame BETWEEN tool rounds (tool_return -> assistant -> usage -> ANOTHER
        // tool_call -> ...). The post-tool usage-completion fallback must NOT treat
        // that inter-round usage tail as terminal and kill the turn before round 2.
        // Regression: on Iroh this manifested as "the turn stops after a tool call
        // and waits for the user to send a message to continue".
        //
        // Telemetry is a global accumulator; clear it so this turn's
        // activeTurn.released event does not pollute other tests that assert
        // on Telemetry.snapshot() (they select with .first{}).
        com.letta.mobile.util.Telemetry.clear()
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            // Round 1: tool return, assistant text, then an inter-round usage tail.
            client.emit(streamDelta(messageType = "client_tool_end", runId = "run-1", toolCallId = "call-1"))
            assertIs<RuntimeEventPayload.ToolReturnObserved>(awaitItem().payload)

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-1"))
            assertEquals("assistant_message", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)

            // Inter-round usage tail — the model is NOT done; another tool call follows.
            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-1"))
            runCurrent()

            // Round 2: the model makes another tool call. If the engine prematurely
            // completed on the inter-round usage tail, this frame is never delivered
            // (the flow already terminated) and the awaitItem() below times out.
            client.emit(streamDelta(messageType = "client_tool_start", runId = "run-1", toolCallId = "call-2"))
            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)

            client.emit(streamDelta(messageType = "client_tool_end", runId = "run-1", toolCallId = "call-2"))
            assertIs<RuntimeEventPayload.ToolReturnObserved>(awaitItem().payload)

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-1"))
            assertEquals("assistant_message", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)

            // Real terminal now completes the turn. The KEY assertion of this test
            // already passed: round 2's tool_call/return were delivered, proving the
            // turn continued past the inter-round usage tail instead of prematurely
            // completing. Drain remaining terminal frames without over-asserting
            // exact tail ordering (buffered stop + completion interplay is covered
            // by the dedicated single-round tests).
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1", stopReason = "end_turn"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun usageBeforeFinalAssistantTextEmitsAfterAssistantAtTail() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-1"))
            runCurrent()
            expectNoEvents()

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-1"))
            assertEquals("assistant_message", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            assertEquals("usage_statistics", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }

    @Test
    fun multipleStopReasonsUseLastAtTail() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1", stopReason = "requires_approval"))
            runCurrent()
            expectNoEvents()

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-1"))
            assertEquals("assistant_message", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1", stopReason = "end_turn"))
            val stop = assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload)
            assertEquals("stop_reason", stop.messageType)
            assertEquals("end_turn", stop.body.jsonDeltaString("stop_reason"))
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }

    @Test
    fun multipleUsageFramesUseFirstAtTail() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-1", totalTokens = 11))
            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-1", totalTokens = 22))
            runCurrent()
            expectNoEvents()

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val usage = assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload)
            assertEquals("usage_statistics", usage.messageType)
            assertEquals("11", usage.body.jsonDeltaString("total_tokens"))
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }

    @Test
    fun failedTurnFlushesBufferedTailBeforeFailedTerminal() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-1"))
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1", stopReason = "requires_approval"))
            runCurrent()
            expectNoEvents()

            client.emit(streamDelta(messageType = "error_message", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            assertEquals("usage_statistics", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val failed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Failed, failed.status)
            awaitComplete()
        }
    }

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

            val approvalInput = assertIs<AppServerCommand.Input>(client.sentCommands.last())
            assertEquals(runtime, approvalInput.runtime)
            val approval = assertIs<AppServerInputPayload.ApprovalResponse>(approvalInput.payload)
            assertEquals("approval-1", approval.requestId)
            assertIs<AppServerApprovalResponseDecision.Allow>(approval.decision)
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
        // currentTimeMs() reads the wall clock, so the watchdog only advances under
        // REAL time; run this on Dispatchers.Default so its delays are real.
        withContext(Dispatchers.Default) {
            val client = FakeAppServerClient()
            val engine = AppServerTurnEngine(
                client = client,
                permissionMode = AppServerPermissionMode.Unrestricted,
                turnIdleTimeoutMs = 300,
            )
            val payloads = MutableStateFlow<List<RuntimeEventPayload>>(emptyList())
            val job = launch {
                engine.runTurn(command).collect { draft -> payloads.update { it + draft.payload } }
            }

            // An Input command is sent only after the collector subscribes, so
            // waiting for it guarantees the ControlRequest below is observed.
            awaitUntil { client.sentCommands.any { it is AppServerCommand.Input } }

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
            awaitUntil { payloads.value.any { it is RuntimeEventPayload.ApprovalRequested } }

            // Idle far longer than the watchdog window while the approval is
            // outstanding: the watchdog is paused and MUST NOT fail the turn.
            delay(300L * 4)
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
    }

    @Test
    fun normalIdleTurnStillFailsViaWatchdogWhenNoUserInputApproval() = runTest {
        // letta-mobile-vilsn.6: the pause is scoped to outstanding user-input
        // approvals ONLY — a genuinely idle/stuck turn with no such approval must
        // still be force-failed by the idle watchdog.
        withContext(Dispatchers.Default) {
            val client = FakeAppServerClient()
            val engine = AppServerTurnEngine(
                client = client,
                turnIdleTimeoutMs = 150,
            )
            val payloads = MutableStateFlow<List<RuntimeEventPayload>>(emptyList())
            val job = launch {
                engine.runTurn(command).collect { draft -> payloads.update { it + draft.payload } }
            }

            awaitUntil { client.sentCommands.any { it is AppServerCommand.Input } }

            // No further frames ever arrive: the watchdog must synthesize a Failed
            // idle-timeout lifecycle so the shared lock is released.
            awaitUntil(timeoutMs = 4000) {
                payloads.value.any {
                    it is RuntimeEventPayload.RunLifecycleChanged && it.status == RuntimeRunStatus.Failed
                }
            }
            assertTrue(
                payloads.value.any {
                    it is RuntimeEventPayload.RunLifecycleChanged && it.status == RuntimeRunStatus.Failed
                },
                "idle watchdog must still fail a genuinely idle turn",
            )
            job.cancel()
        }
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
        withContext(Dispatchers.Default) {
            val client = FakeAppServerClient()
            val engine = AppServerTurnEngine(
                client = client,
                permissionMode = AppServerPermissionMode.Unrestricted,
                turnIdleTimeoutMs = 300,
            )
            val payloads = MutableStateFlow<List<RuntimeEventPayload>>(emptyList())
            val job = launch {
                engine.runTurn(command).collect { draft -> payloads.update { it + draft.payload } }
            }

            awaitUntil { client.sentCommands.any { it is AppServerCommand.Input } }

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
            awaitUntil { payloads.value.any { it is RuntimeEventPayload.ApprovalRequested } }

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

            // Idle far past the watchdog window: the pause must hold despite the
            // unrelated frame, so no Failed idle-timeout is synthesized.
            delay(300L * 4)
            assertTrue(
                payloads.value.none {
                    it is RuntimeEventPayload.RunLifecycleChanged && it.status == RuntimeRunStatus.Failed
                },
                "an unrelated status frame must not lift the user-input watchdog pause",
            )
            assertTrue(job.isActive, "turn must stay pending while awaiting the user's answer")
            job.cancel()
        }
    }

    @Test
    fun idleWatchdogResumesAndFailsAfterUserInputGateResolvesViaToolReturn() = runTest {
        // letta-mobile-vilsn.6: once the outstanding user-input gate is genuinely
        // resolved (its tool_return arrives — the user answered and the tool ran),
        // the pause is lifted and a subsequent genuine idle DOES force-fail via the
        // watchdog. The pause is per-gate and not sticky, so activeTurn is never
        // left wedged after a real answer.
        withContext(Dispatchers.Default) {
            val client = FakeAppServerClient()
            val engine = AppServerTurnEngine(
                client = client,
                permissionMode = AppServerPermissionMode.Unrestricted,
                turnIdleTimeoutMs = 150,
            )
            val payloads = MutableStateFlow<List<RuntimeEventPayload>>(emptyList())
            val job = launch {
                engine.runTurn(command).collect { draft -> payloads.update { it + draft.payload } }
            }

            awaitUntil { client.sentCommands.any { it is AppServerCommand.Input } }

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
            awaitUntil { payloads.value.any { it is RuntimeEventPayload.ApprovalRequested } }

            // The gate for tool-call-1 resolves via its tool_return.
            client.emit(streamDelta(messageType = "client_tool_end", runId = "run-1", toolCallId = "tool-call-1"))
            awaitUntil { payloads.value.any { it is RuntimeEventPayload.ToolReturnObserved } }

            // Now genuinely idle with the gate cleared: the watchdog must resume and
            // force-fail the turn so the shared lock is released.
            awaitUntil(timeoutMs = 4000) {
                payloads.value.any {
                    it is RuntimeEventPayload.RunLifecycleChanged && it.status == RuntimeRunStatus.Failed
                }
            }
            assertTrue(
                payloads.value.any {
                    it is RuntimeEventPayload.RunLifecycleChanged && it.status == RuntimeRunStatus.Failed
                },
                "idle watchdog must resume and fail once the user-input gate is resolved",
            )
            job.cancel()
        }
    }

    @Test
    fun runTurnPreservesMultimodalContentParts() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)
        val contentParts = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", "look")
                },
                buildJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject {
                        put("type", "base64")
                        put("media_type", "image/png")
                        put("data", "abc123")
                    })
                },
            ),
        )

        engine.runTurn(
            command.copy(input = TurnInput.UserMessage("local-image", "look", contentPartsJson = contentParts.toString())),
        ).test {
            awaitItem()
            val input = assertIs<AppServerCommand.Input>(client.sentCommands.single())
            val payload = assertIs<com.letta.mobile.data.transport.appserver.AppServerInputPayload.CreateMessage>(input.payload)
            val content = payload.messages.single().content.jsonArray
            assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("look", content[0].jsonObject["text"]?.jsonPrimitive?.content)
            assertEquals("image", content[1].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("abc123", content[1].jsonObject["source"]?.jsonObject?.get("data")?.jsonPrimitive?.content)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            awaitItem()
            awaitComplete()
        }
    }

    @Test
    fun activeTurnOwnerIsNullWhenIdle() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        // letta-mobile-kyqdt: no turn has run, so no owner metadata exists.
        assertNull(engine.activeTurnOwner)
    }

    @Test
    fun activeTurnOwnerIsPopulatedWhileActiveAndClearedOnCompletion() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            // letta-mobile-kyqdt: while the turn holds the lock, owner metadata
            // identifies the acquiring run/agent/conversation. Presence + values
            // only — no timing/behavior assertions.
            val owner = engine.activeTurnOwner
            assertNotNull(owner)
            assertEquals("runtime-1", owner.runtimeId)
            assertEquals("agent-1", owner.agentId)
            assertEquals("conv-1", owner.conversationId)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }

        // letta-mobile-kyqdt: the finally block clears owner metadata when the
        // lock is released, so the engine reports idle ownership again.
        assertNull(engine.activeTurnOwner)
    }

    @Test
    fun activeTurnOwnerRunIdIsPromotedWhenStreamFramesRevealServerRunId() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            // letta-mobile-kyqdt P1b: owner.runId is null until a server frame
            // reveals the run id.
            assertNull(engine.activeTurnOwner?.runId)

            // A streamed frame carries the server-assigned run id; the engine
            // promotes it into the owner metadata (pure copy).
            client.emit(streamDelta(messageType = "assistant_message", runId = "run-8c7b6ac1"))
            assertEquals("assistant_message", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)

            val owner = engine.activeTurnOwner
            assertNotNull(owner)
            assertEquals("run-8c7b6ac1", owner.runId)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-8c7b6ac1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }

    @Test
    fun activeTurnOwnerRecordsProcessRoleAndDeadlinesWhileActive() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            permissionMode = AppServerPermissionMode.Unrestricted,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            // letta-mobile-kyqdt P1c/P2: the owner captures the process/permission
            // role plus the settle + watchdog deadlines in force for this turn.
            val owner = engine.activeTurnOwner
            assertNotNull(owner)
            assertEquals(AppServerPermissionMode.Unrestricted.name, owner.processRole)
            assertNotNull(owner.settleDeadlineMs)
            assertNotNull(owner.watchdogDeadlineMs)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            awaitComplete()
        }
    }

    @Test
    fun releasedTelemetryRecordsTerminalSourceScopeMatchAndReleaseReasonOnCompletion() = runTest {
        com.letta.mobile.util.Telemetry.clear()
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }

        // letta-mobile-kyqdt P1c: the released telemetry event carries the
        // terminal diagnostics (source, scope-match) plus the release reason.
        // Presence/value assertions only — no timing assertions.
        val released = com.letta.mobile.util.Telemetry.snapshot().first {
            it.tag == "AppServerTurnEngine" && it.name == "activeTurn.released"
        }
        assertEquals(RuntimeRunStatus.Completed.name, released.attrs["lastTerminal"])
        assertEquals(true, released.attrs["lastTerminalScopeMatched"])
        assertNotNull(released.attrs["lastTerminalSource"])
        assertEquals("normal_completion", released.attrs["releaseReason"])
    }

    @Test
    fun activeTurnOwnerRecordsScopeRejectedTerminalWhenTerminalFailsScopeMatch() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        val otherRuntime = AppServerRuntimeScope("agent-OTHER", "conv-OTHER")
        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            // A terminal-bearing frame arrives scoped to a DIFFERENT runtime, so
            // matches(scope) rejects it. The owner records the rejected scope
            // decision (the leading hypothesis: terminal arrived but failed
            // matches(scope)). This is pure logging: the turn is NOT terminated.
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1", runtime = otherRuntime))
            runCurrent()
            expectNoEvents()

            val owner = engine.activeTurnOwner
            assertNotNull(owner)
            assertEquals(false, owner.lastTerminalScopeMatched)
            assertEquals("scope_rejected_terminal", owner.lastTerminalSource)

            // A correctly-scoped terminal still completes the turn normally
            // (proving the rejected-terminal record did not change behavior).
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }

    @Test
    fun activeTurnOwnerIsNonNullWheneverEngineIsBusy() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        val first = backgroundScope.async {
            engine.runTurn(command).collect()
        }
        runCurrent()

        // letta-mobile-kyqdt P1a: whenever the engine reports busy, the owner
        // metadata MUST be present — there is no "busy but unknown owner" window.
        // (The finally clears the owner strictly AFTER unlock, so busy implies a
        // non-null owner.)
        if (engine.isBusy) {
            assertNotNull(engine.activeTurnOwner)
        }

        client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
        first.await()

        // After release, idle again and owner is cleared.
        assertNull(engine.activeTurnOwner)
    }

    @Test
    fun runTurnRejectsConcurrentTurnsForSameEngine() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        val first = backgroundScope.async {
            engine.runTurn(command).collect()
        }
        runCurrent()

        assertFailsWith<IllegalStateException> {
            engine.runTurn(command.copy(input = TurnInput.UserMessage("local-2", "second"))).take(1).collect()
        }

        client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
        first.await()
    }

    @Test
    fun runTurnStartsNewRuntimeWhenAgentOrConversationChanges() = runTest {
        var requestId = 0
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = {
                requestId += 1
                "runtime-start-$requestId"
            },
        )

        engine.runTurn(command).test {
            awaitItem()
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            awaitItem()
            awaitComplete()
        }

        val secondRuntime = AppServerRuntimeScope("agent-2", "conv-2")
        val secondCommand = command.copy(
            agentId = AgentId(secondRuntime.agentId),
            conversationId = ConversationId(secondRuntime.conversationId),
            input = TurnInput.UserMessage("local-2", "second"),
        )

        engine.runTurn(secondCommand).test {
            awaitItem()
            val input = assertIs<AppServerCommand.Input>(client.sentCommands.last())
            assertEquals(secondRuntime, input.runtime)
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-2", runtime = secondRuntime))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            awaitItem()
            awaitComplete()
        }

        assertEquals(
            listOf("agent-1", "agent-2"),
            client.runtimeStartCommands.map { it.agentId },
        )
        assertEquals(
            listOf("runtime-start-1", "runtime-start-2"),
            client.runtimeStartCommands.map { it.requestId },
        )
    }

    companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(
                localMessageId = "local-1",
                text = "hello",
            ),
        )
    }
}

private class FakeAppServerClient(
    private val onRuntimeStart: () -> Unit = {},
    private val onInput: () -> Unit = {},
) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 16)
    val runtimeStartCommands = mutableListOf<AppServerCommand.RuntimeStart>()
    val sentCommands = mutableListOf<AppServerCommand>()

    override suspend fun auth(command: AppServerCommand.Auth): AppServerInboundFrame.AuthResponse =
        AppServerInboundFrame.AuthResponse(command.requestId, success = true)

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
        onRuntimeStart()
        runtimeStartCommands += command
        return AppServerInboundFrame.RuntimeStartResponse(
            requestId = command.requestId,
            success = true,
            runtime = AppServerRuntimeScope(
                agentId = requireNotNull(command.agentId),
                conversationId = requireNotNull(command.conversationId),
            ),
        )
    }

    override suspend fun input(command: AppServerCommand.Input) {
        onInput()
        sentCommands += command
    }

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        error("sync is not used by these tests")

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        error("abort is not used by these tests")

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
        sentCommands += command
        return AppServerInboundFrame.AdminRpcResponse(
            requestId = command.requestId,
            success = true,
            result = null,
        )
    }

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
        sentCommands += command
    }

    fun emit(frame: AppServerInboundFrame) {
        (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
            AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = frame,
                raw = buildJsonObject {
                    put("type", frame.type ?: "unknown")
                    put("idempotency_key", "evt-1")
                    if (frame is AppServerInboundFrame.StreamDelta) {
                        put("delta", frame.delta)
                    }
                },
            ),
        )
    }
}

private fun streamDelta(
    messageType: String,
    runId: String,
    runtime: AppServerRuntimeScope = AppServerTurnEngineTest.runtime,
    stopReason: String? = null,
    totalTokens: Int? = null,
    toolCallId: String? = null,
): AppServerInboundFrame.StreamDelta =
    AppServerInboundFrame.StreamDelta(
        runtime = runtime,
        eventSeq = 1,
        emittedAt = "2026-06-24T00:00:00Z",
        idempotencyKey = "evt-1",
        delta = buildJsonObject {
            put("message_type", messageType)
            put("run_id", runId)
            if (stopReason != null) put("stop_reason", stopReason)
            if (totalTokens != null) put("total_tokens", totalTokens)
            if (toolCallId != null) put("tool_call_id", toolCallId)
        },
    )

private suspend fun awaitUntil(timeoutMs: Long = 2000, condition: () -> Boolean) {
    var waited = 0L
    while (!condition()) {
        if (waited >= timeoutMs) throw AssertionError("condition not met within ${timeoutMs}ms")
        delay(10)
        waited += 10
    }
}

private fun String.jsonDeltaString(key: String): String? = runCatching {
    val raw = AppServerProtocol.json.parseToJsonElement(this).jsonObject
    val value = raw["delta"]?.jsonObject?.get(key)
    value?.jsonPrimitive?.content
}.getOrNull()
