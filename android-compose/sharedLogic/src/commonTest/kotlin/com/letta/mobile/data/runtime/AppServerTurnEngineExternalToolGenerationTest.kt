package com.letta.mobile.data.runtime

import app.cash.turbine.test
import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.controller.extras.ExternalTool
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.fanout.InboundControlRequestRegistry
import com.letta.mobile.data.model.AgentId
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
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * PR #1040 review follow-ups on the external-tool / inbound-control path.
 *
 * - letta-mobile-lgns8.22.4.1.2 — external-tool execution is fenced by the
 *   VALIDATED lease generation, so a disconnect racing the handler cannot make it
 *   answer (or re-answer) across a generation boundary.
 * - letta-mobile-lgns8.22.4.1.3 — registry identity is (request_id, tool_call_id).
 * - letta-mobile-lgns8.22.4.1.4 — approvals are retired against the CLAIM
 *   generation, never the live one.
 * - letta-mobile-lgns8.22.4.1.6 — a computed tool result outlives its one-way
 *   (ambiguous) send so a reconnect replay reuses it instead of re-invoking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineExternalToolGenerationTest {

    @Test
    fun aDisconnectDuringToolExecutionAbortsTheResponseInsteadOfAnsweringAcrossGenerations() = runTest {
        var generation = 0L
        var invokeCount = 0
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            connectionGenerationProvider = { generation },
            externalToolRegistry = countingRegistry(
                onInvoke = {
                    invokeCount += 1
                    // The transport dies while the (possibly non-idempotent) tool runs.
                    generation = 1L
                },
            ),
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest("ext-1", "tc-1")
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, invokeCount)
        assertTrue(
            client.externalResponses.isEmpty(),
            "a response claimed on generation 0 must not be written onto generation 1",
        )
    }

    @Test
    fun theCachedResultOfAnAbortedSendAnswersTheReconnectReplayWithoutReinvoking() = runTest {
        var generation = 0L
        var invokeCount = 0
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            connectionGenerationProvider = { generation },
            externalToolRegistry = countingRegistry(
                onInvoke = {
                    invokeCount += 1
                    if (generation == 0L) generation = 1L
                },
            ),
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest("ext-1", "tc-1")
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(client.externalResponses.isEmpty())

        // Reconnect: the server replays the still-blocking request on generation 1.
        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest("ext-1", "tc-1")
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, client.externalResponses.size, "the replay is answered")
        assertEquals("ext-1", client.externalResponses.single().requestId)
        assertEquals(1, invokeCount, "a non-idempotent tool must never run twice for one request identity")
    }

    @Test
    fun aResultSurvivesASuccessfulButAmbiguousSendSoAReplayReusesIt() = runTest {
        var generation = 0L
        var invokeCount = 0
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            connectionGenerationProvider = { generation },
            externalToolRegistry = countingRegistry(onInvoke = { invokeCount += 1 }),
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest("ext-1", "tc-1")
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, client.externalResponses.size)
        assertEquals(1, invokeCount)

        // sendExternalToolResponse is ONE-WAY: returning normally does not prove
        // the App Server received it. The connection drops and the server replays
        // the same request on the successor generation.
        generation = 1L
        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest("ext-1", "tc-1")
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(2, client.externalResponses.size, "the replay is answered again")
        assertEquals(
            1,
            invokeCount,
            "the retained result answers the replay; the tool must NOT be re-invoked",
        )
    }

    @Test
    fun sameRequestIdWithADistinctToolCallIdIsExecutedAndAnsweredSeparately() = runTest {
        val invoked = mutableListOf<String>()
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            externalToolRegistry = countingRegistry(onInvoke = { invoked += "call" }),
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest("ext-shared", "tc-a")
            runCurrent()
            client.emitExternalToolCallRequest("ext-shared", "tc-b")
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            2,
            client.externalResponses.size,
            "request_id_and_tool_call_id is the App Server v2 idempotency key: " +
                "a reused request_id with a new tool_call_id is a NEW call and must be answered",
        )
        assertEquals(2, invoked.size)
    }

    @Test
    fun autoApprovalRetiresTheClaimGenerationNotAGenerationInstalledMidSend() = runTest {
        var generation = 0L
        val registry = InboundControlRequestRegistry()
        val client = CapturingClient(
            onApprovalResponse = {
                // A disconnect lands while the approval decision is in flight and
                // the recovery replay is registered on the successor generation.
                generation = 1L
                registry.register(
                    InboundControlRequestRegistry.RegisterRequest(
                        requestId = "approval-1",
                        kind = InboundControlRequestRegistry.Kind.Approval,
                        connectionGeneration = 1L,
                    ),
                )
            },
        )
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            permissionMode = AppServerPermissionMode.Unrestricted,
            connectionGenerationProvider = { generation },
            inboundControlRegistry = registry,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitApprovalControlRequest("approval-1", "tool-call-1")
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            InboundControlRequestRegistry.State.Answered,
            registry.lookup(controlRef("approval-1"), connectionGeneration = 0L)?.state,
            "the generation the decision was actually sent on is retired",
        )
        assertTrue(
            registry.isDeliverableTo(controlRef("approval-1"), leaseToken = 99L, connectionGeneration = 1L),
            "the successor generation's recovery replay must stay deliverable — the " +
                "server may never have received the old-connection decision",
        )
    }

    @Test
    fun explicitAnswerAgainstAStaleClaimGenerationDoesNotRetireTheSuccessorReplay() = runTest {
        var generation = 0L
        val registry = InboundControlRequestRegistry()
        val engine = AppServerTurnEngine(
            client = CapturingClient(),
            connectionGenerationProvider = { generation },
            inboundControlRegistry = registry,
        )
        registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "approval-9",
                kind = InboundControlRequestRegistry.Kind.Approval,
                connectionGeneration = 0L,
            ),
        )
        // Reconnect + recovery replay while the decision is in flight.
        generation = 1L
        registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "approval-9",
                kind = InboundControlRequestRegistry.Kind.Approval,
                connectionGeneration = 1L,
            ),
        )

        engine.markInboundControlAnswered("approval-9", claimGeneration = 0L)

        assertTrue(
            registry.isDeliverableTo(controlRef("approval-9"), leaseToken = 1L, connectionGeneration = 1L),
            "an answer sent on generation 0 must not retire the generation-1 replay",
        )
    }

    private fun countingRegistry(onInvoke: suspend () -> Unit) = ExternalToolRegistry(
        tools = listOf(
            object : ExternalTool {
                override val name = "echo"
                override val description = "counting echo"
                override val inputSchema: JsonObject? = null
                override val capability = Capability.SlimAgents
                override suspend fun invoke(input: JsonObject): ExternalToolResult {
                    onInvoke()
                    return ExternalToolResult.Success("ok")
                }
            },
        ),
        capabilities = RemoteCapabilities(slimAgents = true),
    )

    private companion object {
        /**
         * The idle watchdog reads the WALL CLOCK while runTest skips virtual
         * delays, so a short window makes these tests race real host scheduling
         * (they were flaky under load with 50ms). Every test here drives the turn
         * to an explicit cancel, so the watchdog must simply never fire.
         */
        const val TEST_IDLE_TIMEOUT_MS = 60_000L

        val command = TurnCommand(
            backendId = BackendId("iroh-app-server"),
            runtimeId = RuntimeId("iroh:test"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
    }

    private class CapturingClient(
        /** Invoked only for ApprovalResponse inputs (not the turn's CreateMessage). */
        private val onApprovalResponse: () -> Unit = {},
    ) : AppServerClient {
        private val eventFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 32)
        override val events: Flow<AppServerReceivedFrame> = eventFlow

        val externalResponses = mutableListOf<AppServerCommand.ExternalToolCallResponse>()

        override suspend fun runtimeStart(
            command: AppServerCommand.RuntimeStart,
        ): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) {
            if (command.payload is AppServerInputPayload.ApprovalResponse) onApprovalResponse()
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
            externalResponses += command
        }

        fun emitExternalToolCallRequest(requestId: String, toolCallId: String) {
            eventFlow.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Control,
                    raw = buildJsonObject { put("type", "external_tool_call_request") },
                    frame = AppServerInboundFrame.ExternalToolCallRequest(
                        requestId = requestId,
                        runtime = runtime,
                        toolCallId = toolCallId,
                        toolName = "echo",
                        input = buildJsonObject { put("text", "hi") },
                    ),
                ),
            )
        }

        fun emitApprovalControlRequest(requestId: String, toolCallId: String) {
            eventFlow.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Control,
                    raw = buildJsonObject { put("type", "control_request") },
                    frame = AppServerInboundFrame.ControlRequest(
                        requestId = requestId,
                        request = buildJsonObject {
                            put("subtype", "can_use_tool")
                            put("tool_name", "searxng_web_search")
                            put("tool_call_id", toolCallId)
                            put("input", buildJsonObject { put("query", "iroh") })
                        },
                        agentId = runtime.agentId,
                        conversationId = runtime.conversationId,
                    ),
                ),
            )
        }
    }
}

/** Shorthand for the (request_id, tool_call_id) identity (lgns8.22.4.1.3). */
private fun controlRef(requestId: String, toolCallId: String? = null) =
    InboundControlRequestRegistry.RequestRef(requestId, toolCallId)
