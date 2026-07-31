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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-lgns8.17 REMAINDER — the four gaps left after the guarantor core
 * (#995) and the fencing/caching work (#1061, #1065).
 *
 * (a) runtime_start must ADVERTISE the registry's tools. `external_tools` is the
 *     only registration seam the App Server has; nothing wrote it, so the field
 *     was dead and the guarantee could never be exercised in production.
 * (b) a generation flip racing the SEND must still leave the re-emitted request
 *     answerable, idempotently, from the retained result.
 * (c) `ExternalTool.invoke` must be bounded and must not run on the collect loop
 *     (the fanout awaits bounded per-subscriber sends, so a slow tool stalled
 *     frame ingestion for every runtime on the connection).
 * (d) a request with NO active turn lease must still be answered — the original
 *     hang class, one level up from the in-turn case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineExternalToolRemainderTest {

    // ---------------------------------------------------------------- (a)

    @Test
    fun runtimeStartAdvertisesTheRegistrysToolsSoTheServerCanEverEmitARequest() = runTest {
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            externalToolRegistry = registryOf(EchoTool),
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            cancelAndIgnoreRemainingEvents()
        }

        val started = client.runtimeStarts.single()
        val advertised = started.externalTools
            ?.flatMap { group -> group.tools }
            ?.map { it.name }
            .orEmpty()
        assertContains(
            advertised,
            "echo",
            "runtime_start MUST carry external_tools; the App Server registers exactly this " +
                "list and never emits an external_tool_call_request for a name absent from it",
        )
        val definition = started.externalTools!!.single().tools.single()
        assertEquals("test echo tool", definition.description)
        assertEquals(
            "object",
            definition.parameters["type"].toString().trim('"'),
            "a null inputSchema must still serialise to a valid empty object schema",
        )
    }

    @Test
    fun anEmptyRegistryOmitsTheExternalToolsFieldEntirely() = runTest {
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            externalToolRegistry = ExternalToolRegistry.factoryDefault(),
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(
            client.runtimeStarts.single().externalTools,
            "the factory default advertises nothing (every extra tool is an unimplemented " +
                "stub), so the field is omitted rather than sent as an empty group",
        )
    }

    // ---------------------------------------------------------------- (b)

    @Test
    fun aGenerationFlipDuringTheSendStillAnswersTheReemittedRequestWithoutReinvoking() = runTest {
        var generation = 0L
        var invokeCount = 0
        val client = CapturingClient()
        // The transport dies WHILE the one-way response is being written: the send
        // returns normally (AmbiguousMutation) but the server never saw it, so it
        // re-emits the still-blocking request on the successor generation.
        client.onSendExternalToolResponse = { if (generation == 0L) generation = 1L }
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            connectionGenerationProvider = { generation },
            externalToolRegistry = registryOf(
                countingTool { invokeCount += 1; ExternalToolResult.Success("computed-once") },
            ),
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest("ext-flip", "tc-flip")
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, client.externalResponses.size, "the first send happened (ambiguously)")
        assertEquals(1L, generation, "the generation flipped mid-send")

        // Reconnect replay on generation 1.
        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest("ext-flip", "tc-flip")
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            2,
            client.externalResponses.size,
            "a mid-send generation flip must NOT retire the request: markAnswered runs against " +
                "the stale CLAIM generation, so the successor replay is still claimable and answered",
        )
        assertEquals("ext-flip", client.externalResponses.last().requestId)
        assertEquals(
            "computed-once",
            client.externalResponses.last().result?.content?.single()?.text,
            "the replay is answered idempotently from the retained result",
        )
        assertEquals(1, invokeCount, "the tool must never run twice for one request identity")
    }

    // ---------------------------------------------------------------- (c)

    @Test
    fun aHungExternalToolTimesOutWithAMatchedIsErrorSoTheTurnStillTerminates() = runTest {
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            externalToolRegistry = registryOf(
                countingTool {
                    delay(Long.MAX_VALUE / 2) // never returns
                    ExternalToolResult.Success("unreachable")
                },
            ),
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest("ext-hang", "tc-hang")
            runCurrent()
            assertTrue(
                client.externalResponses.isEmpty(),
                "the tool is still running — nothing is answered yet",
            )
            advanceTimeBy(AppServerTurnEngine.EXTERNAL_TOOL_INVOCATION_TIMEOUT_MS + 1)
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }

        val sent = client.externalResponses.single()
        assertEquals("ext-hang", sent.requestId, "the deadline response still matches by request_id")
        assertEquals(true, sent.result?.isError)
        assertContains(sent.result?.content?.single()?.text.orEmpty(), "timed out")
    }

    @Test
    fun aSlowExternalToolDoesNotBlockIngestionOfLaterFrames() = runTest {
        val release = CompletableDeferred<Unit>()
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
            externalToolRegistry = registryOf(
                countingTool {
                    release.await()
                    ExternalToolResult.Success("slow-done")
                },
            ),
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emitExternalToolCallRequest("ext-slow", "tc-slow")
            // The UI draft for the SLOW call is mapped without waiting on the tool.
            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)
            assertTrue(client.externalResponses.isEmpty(), "the slow tool has not returned")

            // A LATER frame must still be ingested while the slow tool runs. If
            // invoke were awaited on the collect loop this would never arrive.
            client.emitExternalToolCallRequest("ext-next", "tc-next")
            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)

            release.complete(Unit)
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            setOf("ext-slow", "ext-next"),
            client.externalResponses.map { it.requestId }.toSet(),
            "both requests are answered; the slow one never starved the frame loop",
        )
    }

    // ---------------------------------------------------------------- (d)

    @Test
    fun anExternalToolRequestWithNoActiveTurnLeaseIsStillAnswered() = runTest {
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
        )

        // No turn has ever run: nothing holds a lease, so no collect loop exists
        // and the request would otherwise block the App Server for its full
        // 5-minute EXTERNAL_TOOL_CALL_TIMEOUT_MS.
        val answered = engine.answerUnleasedExternalToolCall(
            externalToolCallRequest("ext-orphan", "tc-orphan"),
        )

        assertTrue(answered, "an unleased request MUST be taken over and answered")
        val sent = client.externalResponses.single()
        assertEquals("ext-orphan", sent.requestId)
        assertEquals(true, sent.result?.isError, "no controller tool handles it -> matched is_error")
    }

    @Test
    fun anUnleasedAnswerDefersToALiveTurnThatOwnsTheSameRuntime() = runTest {
        val client = CapturingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = TEST_IDLE_TIMEOUT_MS,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            val answered = engine.answerUnleasedExternalToolCall(
                externalToolCallRequest("ext-live", "tc-live"),
            )
            assertFalse(
                answered,
                "a lease is held for this runtime — the turn's collect loop owns the answer " +
                    "(and the UI ToolCallObserved draft it emits)",
            )
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(client.externalResponses.isEmpty())
    }

    // ---------------------------------------------------------------- helpers

    private fun registryOf(vararg tools: ExternalTool) = ExternalToolRegistry(
        tools = tools.toList(),
        capabilities = RemoteCapabilities(slimAgents = true),
    )

    private fun countingTool(body: suspend () -> ExternalToolResult) = object : ExternalTool {
        override val name = "echo"
        override val description = "test echo tool"
        override val inputSchema: JsonObject? = null
        override val capability = Capability.SlimAgents
        override suspend fun invoke(input: JsonObject): ExternalToolResult = body()
    }

    private object EchoTool : ExternalTool {
        override val name = "echo"
        override val description = "test echo tool"
        override val inputSchema: JsonObject? = null
        override val capability = Capability.SlimAgents
        override suspend fun invoke(input: JsonObject): ExternalToolResult =
            ExternalToolResult.Success("echoed")
    }

    private companion object {
        /**
         * The idle watchdog reads the WALL CLOCK while runTest skips virtual
         * delays, so a short window would race real host scheduling. Every test
         * here drives the turn to an explicit cancel: the watchdog must never fire.
         */
        const val TEST_IDLE_TIMEOUT_MS = 600_000L

        val command = TurnCommand(
            backendId = BackendId("iroh-app-server"),
            runtimeId = RuntimeId("iroh:test"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")

        fun externalToolCallRequest(requestId: String, toolCallId: String) =
            AppServerInboundFrame.ExternalToolCallRequest(
                requestId = requestId,
                runtime = runtime,
                toolCallId = toolCallId,
                toolName = "echo",
                input = buildJsonObject { put("text", "hi") },
            )
    }

    private class CapturingClient : AppServerClient {
        private val eventFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 32)
        override val events: Flow<AppServerReceivedFrame> = eventFlow

        val externalResponses = mutableListOf<AppServerCommand.ExternalToolCallResponse>()
        val runtimeStarts = mutableListOf<AppServerCommand.RuntimeStart>()
        var onSendExternalToolResponse: () -> Unit = {}

        override suspend fun runtimeStart(
            command: AppServerCommand.RuntimeStart,
        ): AppServerInboundFrame.RuntimeStartResponse {
            runtimeStarts += command
            return AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )
        }

        override suspend fun input(command: AppServerCommand.Input) = Unit
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
            onSendExternalToolResponse()
            externalResponses += command
        }

        fun emitExternalToolCallRequest(requestId: String, toolCallId: String) {
            val frame = externalToolCallRequest(requestId, toolCallId)
            eventFlow.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Control,
                    raw = buildJsonObject {
                        put("type", "external_tool_call_request")
                        put("request_id", requestId)
                        put("tool_call_id", toolCallId)
                        put("tool_name", "echo")
                        put("runtime", buildJsonObject {
                            put("agent_id", runtime.agentId)
                            put("conversation_id", runtime.conversationId)
                        })
                    },
                    frame = frame,
                ),
            )
        }
    }
}
