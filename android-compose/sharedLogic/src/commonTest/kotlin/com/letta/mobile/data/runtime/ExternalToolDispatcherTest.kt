package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.controller.extras.ExternalTool
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.fanout.InboundControlRequestRegistry
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-lgns8.22.5: unit cover for the extracted external-tool seam.
 *
 * The invocation lifecycle used to be reachable only by driving a whole turn
 * through AppServerTurnEngine. These exercise it directly: the ALWAYS-ANSWER
 * guarantee, the bounded invocation, the generation fences either side of the
 * suspension point, and the result cache that makes a replay idempotent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalToolDispatcherTest {

    private val runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-1")

    @Test
    fun answersWithSynthesizedErrorWhenNoRegistryHandlesTheTool() = runTest {
        val client = RecordingClient()
        val dispatcher = dispatcher(client, registry = null)

        dispatcher.answer(request("req-1", "call_1"), leaseToken = 1L, validatedGeneration = 0L)

        val sent = client.responses.single()
        assertEquals("req-1", sent.requestId)
        assertEquals(true, sent.result?.isError)
        assertTrue(sent.result?.content?.single()?.text.orEmpty().contains("not handled"))
    }

    @Test
    fun answersWithTheToolResultWhenTheRegistryHandlesIt() = runTest {
        val client = RecordingClient()
        val dispatcher = dispatcher(client, registry = registryOf(EchoTool { ExternalToolResult.Success("echoed") }))

        dispatcher.answer(request("req-1", "call_1"), leaseToken = 1L, validatedGeneration = 0L)

        val sent = client.responses.single()
        assertEquals("echoed", sent.result?.content?.single()?.text)
        assertEquals(false, sent.result?.isError)
    }

    @Test
    fun aThrowingToolStillProducesAMatchedErrorResponse() = runTest {
        val client = RecordingClient()
        val dispatcher = dispatcher(
            client,
            registry = registryOf(EchoTool { throw IllegalStateException("boom") }),
        )

        dispatcher.answer(request("req-1", "call_1"), leaseToken = 1L, validatedGeneration = 0L)

        val sent = client.responses.single()
        assertEquals("req-1", sent.requestId)
        assertEquals(true, sent.result?.isError)
        assertTrue(sent.result?.content?.single()?.text.orEmpty().contains("boom"))
    }

    @Test
    fun aHangingToolIsBoundedByTheInvocationDeadline() = runTest {
        val client = RecordingClient()
        val dispatcher = dispatcher(
            client,
            registry = registryOf(
                EchoTool {
                    delay(ExternalToolDispatcher.INVOCATION_TIMEOUT_MS * 10)
                    ExternalToolResult.Success("never")
                },
            ),
        )

        dispatcher.answer(request("req-1", "call_1"), leaseToken = 1L, validatedGeneration = 0L)

        val sent = client.responses.single()
        assertEquals(true, sent.result?.isError)
        assertTrue(sent.result?.content?.single()?.text.orEmpty().contains("timed out"))
    }

    @Test
    fun aStaleGenerationAbortsBeforeInvokingTheToolAndReturnsTheClaim() = runTest {
        val client = RecordingClient()
        val controlRegistry = InboundControlRequestRegistry()
        var invocations = 0
        // Live generation has already moved past the claim generation.
        val dispatcher = dispatcher(
            client,
            registry = registryOf(EchoTool { invocations += 1; ExternalToolResult.Success("ok") }),
            controlRegistry = controlRegistry,
            generation = { 1L },
        )

        dispatcher.answer(request("req-1", "call_1"), leaseToken = 1L, validatedGeneration = 0L)

        assertEquals(0, invocations)
        assertTrue(client.responses.isEmpty())
        val ref = InboundControlRequestRegistry.RequestRef("req-1", "call_1")
        assertEquals(
            InboundControlRequestRegistry.State.Pending,
            assertNotNull(controlRegistry.lookup(ref, 0L)).state,
        )
    }

    @Test
    fun aReplayReusesTheCachedResultInsteadOfReInvokingTheTool() = runTest {
        val client = RecordingClient()
        var invocations = 0
        val cache = ExternalToolResultCache()
        val registry = registryOf(EchoTool { invocations += 1; ExternalToolResult.Success("once") })

        // Two independent dispatch passes sharing the cache — the reconnect replay
        // shape, where the second answer must not re-run a non-idempotent tool.
        dispatcher(client, registry, cache = cache)
            .answer(request("req-1", "call_1"), leaseToken = 1L, validatedGeneration = 0L)
        dispatcher(client, registry, cache = cache)
            .answer(request("req-1", "call_1"), leaseToken = 2L, validatedGeneration = 0L)

        assertEquals(1, invocations)
        assertEquals(2, client.responses.size)
        assertTrue(client.responses.all { it.result?.content?.single()?.text == "once" })
    }

    @Test
    fun aSendFailureReleasesTheClaimSoTheReEmittedRequestIsAnswerable() = runTest {
        val client = RecordingClient()
        client.failSend = true
        val controlRegistry = InboundControlRequestRegistry()
        val dispatcher = dispatcher(client, registry = null, controlRegistry = controlRegistry)

        dispatcher.answer(request("req-1", "call_1"), leaseToken = 1L, validatedGeneration = 0L)

        val ref = InboundControlRequestRegistry.RequestRef("req-1", "call_1")
        assertEquals(
            InboundControlRequestRegistry.State.Pending,
            assertNotNull(controlRegistry.lookup(ref, 0L)).state,
        )
    }

    @Test
    fun aSuccessfulSendRetiresTheRequestIdentity() = runTest {
        val client = RecordingClient()
        val controlRegistry = InboundControlRequestRegistry()
        val dispatcher = dispatcher(client, registry = null, controlRegistry = controlRegistry)

        dispatcher.answer(request("req-1", "call_1"), leaseToken = 1L, validatedGeneration = 0L)

        val ref = InboundControlRequestRegistry.RequestRef("req-1", "call_1")
        assertEquals(
            InboundControlRequestRegistry.State.Answered,
            assertNotNull(controlRegistry.lookup(ref, 0L)).state,
        )
    }

    @Test
    fun aSecondClaimHolderDoesNotDoubleAnswer() = runTest {
        val client = RecordingClient()
        val controlRegistry = InboundControlRequestRegistry()
        val dispatcher = dispatcher(client, registry = null, controlRegistry = controlRegistry)
        val ref = InboundControlRequestRegistry.RequestRef("req-1", "call_1")
        controlRegistry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "req-1",
                kind = InboundControlRequestRegistry.Kind.ExternalTool,
                connectionGeneration = 0L,
                toolCallId = "call_1",
            ),
        )
        // Another lease already owns the claim.
        assertTrue(controlRegistry.tryClaim(ref, leaseToken = 9L, connectionGeneration = 0L))

        dispatcher.answer(request("req-1", "call_1"), leaseToken = 1L, validatedGeneration = 0L)

        assertTrue(client.responses.isEmpty())
    }

    // ---------------------------------------------------------------- helpers

    private fun dispatcher(
        client: AppServerClient,
        registry: ExternalToolRegistry?,
        controlRegistry: InboundControlRequestRegistry = InboundControlRequestRegistry(),
        generation: () -> Long = { 0L },
        cache: ExternalToolResultCache = ExternalToolResultCache(),
    ) = ExternalToolDispatcher(
        client = client,
        externalToolRegistry = registry,
        inboundControlRegistry = controlRegistry,
        connectionGenerationProvider = generation,
        resultCache = cache,
    )

    private fun registryOf(tool: ExternalTool) =
        ExternalToolRegistry(tools = listOf(tool), capabilities = RemoteCapabilities(slimAgents = true))

    private fun request(requestId: String, toolCallId: String) =
        AppServerInboundFrame.ExternalToolCallRequest(
            requestId = requestId,
            runtime = runtime,
            toolCallId = toolCallId,
            toolName = "echo",
            input = buildJsonObject { put("text", "hi") },
        )

    private class EchoTool(
        private val body: suspend () -> ExternalToolResult,
    ) : ExternalTool {
        override val name: String = "echo"
        override val description: String = "test echo tool"
        override val capability: Capability = Capability.SlimAgents
        override val inputSchema: JsonObject? = null
        override suspend fun invoke(input: JsonObject): ExternalToolResult = body()
    }

    private class RecordingClient : AppServerClient {
        val responses = mutableListOf<AppServerCommand.ExternalToolCallResponse>()
        var failSend = false

        override val events: Flow<AppServerReceivedFrame> = emptyFlow()

        override suspend fun runtimeStart(
            command: AppServerCommand.RuntimeStart,
        ): AppServerInboundFrame.RuntimeStartResponse = error("unused")

        override suspend fun input(command: AppServerCommand.Input) = Unit
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
            if (failSend) throw IllegalStateException("send failed")
            responses += command
        }
    }
}
