package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.controller.fanout.InboundControlRequestRegistry
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.ExternalToolDispatcher
import com.letta.mobile.data.runtime.ExternalToolResultCache
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec): integration coverage for the
 * agent→agent Iroh messaging tool wiring. Pinned against the bead's
 * acceptance criteria:
 *
 *   - "An agent invoking the custom tool sends a message that lands on the
 *      target agent's turn via the Iroh path (NOT via Matrix)."
 *   - "The wrapper log on the SENDING side shows a2a.create_and_deliver."
 *   - "The wrapper log on the RECEIVING side shows a2a.recv ... outcome=success."
 *   - "Per-agent injection mechanism documented and tested."
 *
 * The integration scope here is bounded to what we can test without a live
 * Iroh endpoint: the dispatcher -> registry -> tool -> runner chain. The
 * runner is stubbed to return the wire outcomes (Delivered / Unaddressable /
 * Failed) and asserts the runtime scope's `agentId` reaches the tool as
 * `--from`. The real a2a.create_and_deliver / a2a.recv log assertions are
 * verified at the CLI level via the verification commands in the dispatch
 * brief — those need a live Iroh endpoint and an upstream agent process.
 *
 * Regression for matrix_agent_message: a sibling test in this file pins
 * that the Iroh injection is additive — the existing extras (image_hydration,
 * etc.) still surface when their capabilities are enabled, and the
 * matrix_agent_message path is untouched (it lives in upstream letta-code,
 * which this bead explicitly does not patch).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomIrohMessagingToolIntegrationTest {

    /**
     * BEAD AC: "Per-agent injection mechanism documented and tested."
     * The registry is constructed with `agentMessaging=true` and the tool is
     * wired with the wrapper's binary path. `advertisedToolsCommandGroups()`
     * MUST surface `agent_message_send` so every agent's runtime_start
     * includes it in `external_tools`.
     */
    @Test
    fun agentMessageSendIsAdvertisedWhenCapabilityEnabled() {
        val registry = buildRegistry(binary = "/usr/local/bin/meridian")
        val groups = registry.advertisedToolsCommandGroups(scopeId = "agent-A")
        assertNotNull(groups, "registry must produce a non-null group when capability is on")
        val tools = groups.flatMap { it.tools }
        val names = tools.map { it.name }
        assertTrue(
            "agent_message_send" in names,
            "agent_message_send must be advertised when capability is enabled, got: $names",
        )
        // Description must cross-reference matrix_agent_message so the model
        // picks the right tool for each relationship.
        val irohTool = tools.single { it.name == "agent_message_send" }
        assertTrue(
            irohTool.description.contains("matrix_agent_message"),
            "description should cross-reference matrix_agent_message for the agent↔human surface",
        )
    }

    /**
     * BEAD AC: "An agent invoking the custom tool sends a message that lands
     * on the target agent's turn via the Iroh path (NOT via Matrix)."
     *
     * Drives the dispatcher the way the App Server would: emit an
     * `external_tool_call_request` for `agent_message_send`, with the
     * runtime scope's agentId wired in. Asserts the runner is invoked with
     * exactly that agentId as `--from`, the body reaches the runner
     * unchanged, and the dispatcher's response surfaces a structured
     * success with the wire msgId.
     */
    @Test
    fun dispatcherWiresAgentIdAndBodyToTheRunner() = runTest {
        val captured = CapturingRunner()
        val tool = CustomIrohMessagingTool(
            binary = "/usr/local/bin/meridian",
            identityDir = "/iroh/identities",
            addressStore = "/iroh/agent-addresses.kv",
            runner = captured,
        )
        val registry = buildRegistry(customTool = tool)
        val client = CapturingAppServerClient()
        val dispatcher = ExternalToolDispatcher(
            client = client,
            externalToolRegistry = registry,
            inboundControlRegistry = InboundControlRequestRegistry(),
            connectionGenerationProvider = { 0L },
            resultCache = ExternalToolResultCache(),
        )

        // Drive the dispatcher the way the engine would: emit a tool call
        // request as if it arrived over the WebSocket from the App Server.
        val body = "pm->meridian: status update\nline 2 with \"quotes\" & ampersand https://x.test?a=1"
        client.emitExternalToolCallRequest(
            AppServerInboundFrame.ExternalToolCallRequest(
                requestId = "ext-1",
                toolCallId = "tc-1",
                toolName = CustomIrohMessagingTool.TOOL_NAME,
                runtime = runtimeAgentPm,
                input = buildJsonObject {
                    put("to", "agent-meridian")
                    put("body", body)
                },
            ),
        )

        // Drive the dispatcher until the runner was hit and the response was
        // sent. The dispatcher uses the test dispatcher under runBlocking;
        // we yield a few times to let the launched invocation complete.
        dispatcher.answer(
            request = client.receivedRequests.first(),
            leaseToken = 0L,
            validatedGeneration = 0L,
        )
        // Yield until the runner saw the call (the dispatcher launches the
        // invocation, so we wait for it).
        repeat(20) {
            if (captured.calls.isNotEmpty()) return@repeat
            kotlinx.coroutines.delay(50)
        }

        assertEquals(
            1,
            captured.calls.size,
            "the tool must invoke the runner exactly once",
        )
        val call = captured.calls.single()
        assertEquals("agent-pm", call.fromAgentId, "--from must come from the runtime scope")
        assertEquals("agent-meridian", call.toAgentId)
        assertEquals(body, call.body, "body must round-trip exactly (multi-line, quotes, ampersands, URL)")
        assertEquals("/usr/local/bin/meridian", call.binary)
        assertEquals("/iroh/identities", call.paths.identityDir)
        assertEquals("/iroh/agent-addresses.kv", call.paths.addressStore)

        // The dispatcher sends a matched response so the App Server
        // unblocks the turn. Success carries the runner's msgId.
        assertEquals(1, client.sentResponses.size, "dispatcher must answer every request")
        val response = client.sentResponses.single()
        assertEquals("ext-1", response.requestId)
        assertEquals(false, response.result?.isError, "delivered must surface as success")
        val text = response.result?.content?.single()?.text
        assertNotNull(text)
        assertTrue(
            text.contains("\"delivered\":true"),
            "success body must mark delivered=true, got: $text",
        )
    }

    /**
     * The runner's Unaddressable surfaces as a structured error to the App
     * Server. The dispatcher still sends a matched response — the turn
     * doesn't hang.
     */
    @Test
    fun unaddressableSurfacesAsMatchedIsErrorResponse() = runTest {
        val tool = CustomIrohMessagingTool(
            binary = "/usr/local/bin/meridian",
            runner = FixedRunner(IrohCliSendResult.Unaddressable("agent-meridian", "no_kv_row")),
        )
        val registry = buildRegistry(customTool = tool)
        val client = CapturingAppServerClient()
        val dispatcher = ExternalToolDispatcher(
            client = client,
            externalToolRegistry = registry,
            inboundControlRegistry = InboundControlRequestRegistry(),
            connectionGenerationProvider = { 0L },
            resultCache = ExternalToolResultCache(),
        )
        client.emitExternalToolCallRequest(
            AppServerInboundFrame.ExternalToolCallRequest(
                requestId = "ext-2",
                toolCallId = "tc-2",
                toolName = CustomIrohMessagingTool.TOOL_NAME,
                runtime = runtimeAgentPm,
                input = buildJsonObject {
                    put("to", "agent-meridian")
                    put("body", "hi")
                },
            ),
        )
        dispatcher.answer(
            request = client.receivedRequests.first(),
            leaseToken = 0L,
            validatedGeneration = 0L,
        )
        repeat(20) {
            if (client.sentResponses.isNotEmpty()) return@repeat
            kotlinx.coroutines.delay(50)
        }
        assertEquals(1, client.sentResponses.size)
        val response = client.sentResponses.single()
        assertEquals(true, response.result?.isError, "Unaddressable must surface as is_error")
        val text = response.result?.content?.single()?.text
        assertNotNull(text)
        assertTrue(text.contains("unaddressable"), "reason should mention unaddressable, got: $text")
        assertTrue(text.contains("no_kv_row"), "stderr reason should round-trip, got: $text")
    }

    /**
     * REGRESSION for matrix_agent_message: the new tool injection is
     * additive. The factory-default registry (no `--meridian-binary`) MUST
     * advertise no extras, preserving the pre-bead behavior. The existing
     * extras (`image_hydration`, etc.) must still surface when their
     * capabilities are enabled, regardless of the Iroh capability.
     *
     * Critically: `matrix_agent_message` is a letta-code tool, not a
     * letta-mobile ExternalTool — this bead does not touch it. The
     * "regression" is on the LETTA-MOBILE side: nothing in the existing
     * extras tree moved, and adding `agent_messaging` does not affect the
     * other flags in `RemoteCapabilities`.
     */
    @Test
    fun factoryDefaultRegistryDoesNotAdvertiseIrohAndPreservesExistingExtras() {
        // (1) Factory default: nothing is advertised, regardless of capability flags.
        val factoryDefault = ExternalToolRegistry.factoryDefault()
        assertTrue(factoryDefault.listAdvertisedTools().isEmpty())

        // (2) Without `agentMessaging`, the Iroh tool is NOT advertised even
        //     when an instance is supplied. The capability gate is what
        //     actually controls advertisement — this matches the contract
        //     documented on `ExternalToolRegistry.advertisedTools`.
        val registryWithoutIroh = ExternalToolRegistry.standard(
            capabilities = RemoteCapabilities(imageHydration = true),
            customIrohMessagingTool = CustomIrohMessagingTool(
                binary = "/usr/local/bin/meridian",
                runner = CapturingRunner(),
            ),
        )
        val names = registryWithoutIroh.listAdvertisedTools().map { it.name }.toSet()
        assertTrue(
            "agent_message_send" !in names,
            "Iroh tool must NOT advertise when agentMessaging capability is off, got: $names",
        )
        assertTrue(
            "image_hydration" in names,
            "image_hydration must still surface when its capability is on, regardless of Iroh: $names",
        )

        // (3) With `agentMessaging`, BOTH the Iroh tool AND the existing
        //     extras are advertised — confirming additive composition
        //     rather than replacement. This is the bead's "no regression"
        //     guarantee.
        val registryWithIroh = ExternalToolRegistry.standard(
            capabilities = RemoteCapabilities(agentMessaging = true, imageHydration = true),
            customIrohMessagingTool = CustomIrohMessagingTool(
                binary = "/usr/local/bin/meridian",
                runner = CapturingRunner(),
            ),
        )
        val bothNames = registryWithIroh.listAdvertisedTools().map { it.name }.toSet()
        assertTrue(
            "agent_message_send" in bothNames,
            "Iroh tool must surface when agentMessaging is on: $bothNames",
        )
        assertTrue(
            "image_hydration" in bothNames,
            "image_hydration must STILL surface when BOTH capabilities are on: $bothNames",
        )
    }

    // === helpers ===

    private fun buildRegistry(
        binary: String = "/usr/local/bin/meridian",
        customTool: CustomIrohMessagingTool? = CustomIrohMessagingTool(
            binary = binary,
            runner = CapturingRunner(),
        ),
    ): ExternalToolRegistry = ExternalToolRegistry.standard(
        capabilities = RemoteCapabilities(agentMessaging = true),
        customIrohMessagingTool = customTool,
    )

    /**
     * Minimal [AppServerClient] that captures inbound frames and outbound
     * responses. Lets us drive the dispatcher from a unit test without
     * standing up a real WS server. Adapted from the pattern in
     * [AppServerTurnEngineExternalToolResponseTest].
     */
    private class CapturingAppServerClient : AppServerClient {
        private val inbound: MutableSharedFlow<AppServerReceivedFrame> =
            MutableSharedFlow(extraBufferCapacity = 16)

        /** Captured as the dispatcher reads them off `inbound`. */
        val receivedRequests: MutableList<AppServerInboundFrame.ExternalToolCallRequest> =
            mutableListOf()

        /** Captured as the dispatcher writes them via `sendExternalToolResponse`. */
        val sentResponses: MutableList<AppServerCommand.ExternalToolCallResponse> =
            mutableListOf()

        override val events: Flow<AppServerReceivedFrame> = inbound

        /**
     * Forward an [AppServerInboundFrame.ExternalToolCallRequest] straight to
     * the dispatcher's received-requests queue. Takes the data class
     * directly so callers don't pay the 5-positional-arg cost (CodeScene
     * flags "Excess Number of Function Arguments" on this method).
     */
        fun emitExternalToolCallRequest(
            request: AppServerInboundFrame.ExternalToolCallRequest,
        ) {
            receivedRequests += request
            // Wrap in a `AppServerReceivedFrame` because the channel is the
            // wire-level shape the events flow exposes.
            inbound.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Control,
                    frame = request,
                    raw = kotlinx.serialization.json.buildJsonObject { },
                ),
            )
        }

        override suspend fun runtimeStart(
            command: AppServerCommand.RuntimeStart,
        ): AppServerInboundFrame.RuntimeStartResponse = error("unused")

        override suspend fun sync(
            command: AppServerCommand.Sync,
        ): AppServerInboundFrame.SyncResponse = error("unused")

        override suspend fun abort(
            command: AppServerCommand.AbortMessage,
        ): AppServerInboundFrame.AbortMessageResponse = error("unused")

        override suspend fun input(command: AppServerCommand.Input) = error("unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
            sentResponses += command
        }

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
    }

    companion object {
        private val runtimeAgentPm = AppServerRuntimeScope(
            agentId = "agent-pm",
            conversationId = "conv-pm-1",
        )
    }
}
