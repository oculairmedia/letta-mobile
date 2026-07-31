package com.letta.mobile.data.runtime

import app.cash.turbine.test
import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.controller.extras.ExternalTool
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * lgns8.17: letta-code's App-Server (WS) route does NOT self-execute tool calls —
 * it emits `external_tool_call_request` and BLOCKS the turn until it receives a
 * matched `external_tool_call_response` (matched by request_id; content
 * irrelevant). The turn engine previously mapped the request to a UI
 * ToolCallObserved draft and never replied, so any tool-call turn (Bash etc.)
 * hung with 0 deltas until the idle watchdog force-failed it.
 *
 * The engine must now GUARANTEE a matched response for every request: executed
 * via the wired [ExternalToolRegistry] when it advertises the tool, otherwise a
 * synthesized is_error response so the turn always terminates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineExternalToolResponseTest {

    @Test
    fun anUnhandledToolCallGetsAMatchedIsErrorResponseSoTheTurnDoesNotHang() = runTest {
        val client = CapturingClient() // no registry wired
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            // letta-mobile-pr3wf: the idle window is measured on the INJECTED clock,
            // so a loaded host can no longer trip it mid-assertion.
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            nowMs = { testScheduler.currentTime },
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload) // Started
            client.emitExternalToolCallRequest(
                requestId = "ext-1",
                toolCallId = "tc-1",
                toolName = "Bash",
                input = buildJsonObject { put("command", "ls") },
            )
            // The request is still surfaced to the UI as a tool-call observation.
            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)
            cancelAndIgnoreRemainingEvents()
        }

        val sent = client.externalResponses.single()
        assertEquals("ext-1", sent.requestId, "the response MUST echo the request_id so the App Server matches it")
        assertEquals(
            true,
            sent.result?.isError,
            "no controller tool handles 'Bash' -> a matched is_error response so the turn terminates",
        )
    }

    @Test
    fun aWiredExternalToolReturnsItsSuccessResult() = runTest {
        val registry = ExternalToolRegistry(
            tools = listOf(EchoTool),
            capabilities = RemoteCapabilities(slimAgents = true), // advertise EchoTool's capability
        )
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            // letta-mobile-pr3wf: idle window on the injected virtual clock.
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            nowMs = { testScheduler.currentTime },
            externalToolRegistry = registry,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest(
                requestId = "ext-2",
                toolCallId = "tc-2",
                toolName = "echo",
                input = buildJsonObject { put("text", "hi") },
            )
            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)
            cancelAndIgnoreRemainingEvents()
        }

        val sent = client.externalResponses.single()
        assertEquals("ext-2", sent.requestId)
        assertEquals(false, sent.result?.isError, "a wired, advertised tool returns success")
        assertEquals("echoed: hi", sent.result?.content?.single()?.text)
    }

    @Test
    fun duplicateExternalToolRequestIdIsAnsweredOnlyOnce() = runTest {
        var invokeCount = 0
        val registry = ExternalToolRegistry(
            tools = listOf(
                object : ExternalTool {
                    override val name = "echo"
                    override val description = "counting echo"
                    override val inputSchema: JsonObject? = null
                    override val capability = Capability.SlimAgents
                    override suspend fun invoke(input: JsonObject): ExternalToolResult {
                        invokeCount += 1
                        return ExternalToolResult.Success("ok")
                    }
                },
            ),
            capabilities = RemoteCapabilities(slimAgents = true),
        )
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            // letta-mobile-pr3wf: idle window on the injected virtual clock.
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            nowMs = { testScheduler.currentTime },
            externalToolRegistry = registry,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest(
                requestId = "ext-dup",
                toolCallId = "tc-dup",
                toolName = "echo",
                input = buildJsonObject { put("text", "once") },
            )
            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)
            // Replay the same request_id — must not produce a second response.
            client.emitExternalToolCallRequest(
                requestId = "ext-dup",
                toolCallId = "tc-dup",
                toolName = "echo",
                input = buildJsonObject { put("text", "once") },
            )
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, client.externalResponses.size)
        assertEquals(1, invokeCount)
    }

    private object EchoTool : ExternalTool {
        override val name = "echo"
        override val description = "test echo tool"
        override val inputSchema: JsonObject? = null
        override val capability = Capability.SlimAgents
        override suspend fun invoke(input: JsonObject): ExternalToolResult =
            ExternalToolResult.Success("echoed: ${input["text"]?.jsonPrimitive?.content}")
    }

    private companion object {
        /**
         * letta-mobile-pr3wf: the watchdog USED to read the wall clock while
         * runTest skipped virtual delays, so a short window raced real host
         * scheduling (flaky under load with 50ms) and the window was widened as a
         * stopgap. The engine clock is now injected (`nowMs`), so the window is
         * measured on the SAME virtual timeline the test controls — the wide
         * window is now belt-and-braces rather than the mitigation. Every test
         * here drives the turn to an explicit cancel and never advances virtual
         * time, so the watchdog must simply never fire.
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

    private class CapturingClient : AppServerClient {
        private val eventFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 32)
        override val events: Flow<AppServerReceivedFrame> = eventFlow

        val externalResponses = mutableListOf<AppServerCommand.ExternalToolCallResponse>()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) = Unit
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
            externalResponses += command
        }

        fun emitExternalToolCallRequest(requestId: String, toolCallId: String, toolName: String, input: JsonObject) {
            val raw = buildJsonObject {
                put("type", "external_tool_call_request")
                put("request_id", requestId)
                put("tool_call_id", toolCallId)
                put("tool_name", toolName)
                put("runtime", buildJsonObject {
                    put("agent_id", runtime.agentId)
                    put("conversation_id", runtime.conversationId)
                })
            }
            eventFlow.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Control,
                    raw = raw,
                    frame = AppServerInboundFrame.ExternalToolCallRequest(
                        requestId = requestId,
                        runtime = runtime,
                        toolCallId = toolCallId,
                        toolName = toolName,
                        input = input,
                    ),
                ),
            )
        }
    }
}
