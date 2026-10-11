package com.letta.mobile.data.controller

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.controller.extras.MutableToolSource
import com.letta.mobile.data.controller.extras.ToolAdvertisementState
import com.letta.mobile.data.controller.extras.ToolSource
import com.letta.mobile.data.meridian.AgentToolsMode
import com.letta.mobile.data.meridian.AgentToolsModePolicy
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-s416w.27 (decision R2): a change to the registry's live tool sources is
 * re-advertised to every active runtime with a repeated `runtime_start`.
 *
 * [FakeToolAppServer] keeps a per-runtime tool registry with the semantics observed on the pinned
 * letta-code 0.29.12 (docs/architecture/live-external-tools.md): each `runtime_start` replaces the
 * runtime's tools with its `external_tools`, and an omitted field clears them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalToolReadvertisementTest {
    private val conv1 = AppServerRuntimeScope("agent-1", "conv-1")
    private val conv2 = AppServerRuntimeScope("agent-1", "conv-2")

    @Test
    fun anAddedSourceIsReadvertisedToEveryActiveRuntimeAfterTheDebounce() = harness { server, registry, controller ->
        controller.start(conv1)
        controller.start(conv2)
        server.runtimeStarts.clear()

        registry.addSource(ToolSource.static("plugin.a", listOf(Tool("a_one"))))
        runCurrent()
        assertEquals(ToolAdvertisementState(pending = true), controller.toolAdvertisementState.value)
        assertTrue(server.runtimeStarts.isEmpty(), "nothing is sent inside the debounce window")

        advanceTimeBy(ExternalToolReadvertiser.DEBOUNCE_MS + 1)

        assertEquals(listOf("canvas_open", "a_one"), server.toolsOf(conv1))
        assertEquals(listOf("canvas_open", "a_one"), server.toolsOf(conv2))
        assertEquals(ToolAdvertisementState(pending = false), controller.toolAdvertisementState.value)
        server.runtimeStarts.forEach { command ->
            assertEquals(false, command.recoverApprovals)
            assertEquals(false, command.forceDeviceStatus)
            assertNull(command.mode, "the re-issue leaves the runtime's mode alone")
            assertNull(command.cwd, "the re-issue leaves the runtime's cwd alone")
        }
    }

    @Test
    fun aBurstOfChangesCostsOneRoundWithTheFinalSet() = harness { server, registry, controller ->
        controller.start(conv1)
        server.runtimeStarts.clear()
        val source = MutableToolSource("plugin.a")
        registry.addSource(source)

        source.publish(listOf(Tool("a_one")))
        advanceTimeBy(ExternalToolReadvertiser.DEBOUNCE_MS / 2)
        source.publish(listOf(Tool("a_one"), Tool("a_two")))
        advanceTimeBy(ExternalToolReadvertiser.DEBOUNCE_MS + 1)

        assertEquals(1, server.runtimeStarts.size)
        assertEquals(listOf("canvas_open", "a_one", "a_two"), server.toolsOf(conv1))
    }

    @Test
    fun aRemovedSourceIsWithdrawnFromTheRuntime() = harness { server, registry, controller ->
        registry.addSource(ToolSource.static("plugin.a", listOf(Tool("a_one"))))
        controller.start(conv1)
        assertEquals(listOf("canvas_open", "a_one"), server.toolsOf(conv1))

        registry.removeSource("plugin.a")
        advanceTimeBy(ExternalToolReadvertiser.DEBOUNCE_MS + 1)

        assertEquals(listOf("canvas_open"), server.toolsOf(conv1))
    }

    @Test
    fun withdrawingTheLastToolClearsTheRuntimesTools() = harness(fixed = emptyList()) { server, registry, controller ->
        registry.addSource(ToolSource.static("plugin.a", listOf(Tool("a_one"))))
        controller.start(conv1)

        registry.removeSource("plugin.a")
        advanceTimeBy(ExternalToolReadvertiser.DEBOUNCE_MS + 1)

        assertNull(server.runtimeStarts.last().externalTools, "an empty set is the omitted field")
        assertEquals(emptyList<String>(), server.toolsOf(conv1))
    }

    @Test
    fun aRegistryWithOnlyFixedToolsNeverReissuesRuntimeStart() = harness { server, _, controller ->
        controller.start(conv1)
        advanceTimeBy(ExternalToolReadvertiser.DEBOUNCE_MS * 10)

        assertEquals(1, server.runtimeStarts.size)
        assertEquals(ToolAdvertisementState(pending = false), controller.toolAdvertisementState.value)
    }

    @Test
    fun aFailedReissueIsSurvivedAndTheNextChangeStillLands() = harness { server, registry, controller ->
        controller.start(conv1)
        server.failNextRuntimeStart = true

        registry.addSource(ToolSource.static("plugin.a", listOf(Tool("a_one"))))
        advanceTimeBy(ExternalToolReadvertiser.DEBOUNCE_MS + 1)
        assertEquals(listOf("canvas_open"), server.toolsOf(conv1), "the failed re-issue left the old set")
        assertEquals(ToolAdvertisementState(pending = false), controller.toolAdvertisementState.value)

        registry.addSource(ToolSource.static("plugin.b", listOf(Tool("b_one"))))
        advanceTimeBy(ExternalToolReadvertiser.DEBOUNCE_MS + 1)

        assertEquals(listOf("canvas_open", "a_one", "b_one"), server.toolsOf(conv1))
    }

    @Test
    fun aRuntimeDroppedByADisconnectIsLeftToTheReconnect() = harness { server, registry, controller ->
        controller.start(conv1)
        controller.onTransportDisconnected("test")
        server.runtimeStarts.clear()

        registry.addSource(ToolSource.static("plugin.a", listOf(Tool("a_one"))))
        advanceTimeBy(ExternalToolReadvertiser.DEBOUNCE_MS + 1)

        assertTrue(server.runtimeStarts.isEmpty())
    }

    /** letta-mobile-jna0o.9: each runtime_start, first or re-issued, carries its own agent's agent-tools-mode. */
    @Test
    fun eachRuntimeIsAdvertisedItsAgentsToolsMode() = harness(
        modes = AgentToolsModePolicy(perAgent = mapOf("agent-2" to AgentToolsMode.META, "agent-3" to AgentToolsMode.CLI)),
    ) { server, registry, controller ->
        val meta = AppServerRuntimeScope("agent-2", "conv-3")
        val cli = AppServerRuntimeScope("agent-3", "conv-4")
        controller.start(conv1)
        controller.start(meta)
        controller.start(cli)
        assertEquals(listOf("canvas_open"), server.toolsOf(conv1))
        assertEquals(listOf("meridian"), server.toolsOf(meta))
        assertNull(server.runtimeStarts.last().externalTools, "cli advertises nothing")

        registry.addSource(ToolSource.static("plugin.a", listOf(Tool("a_one"))))
        advanceTimeBy(ExternalToolReadvertiser.DEBOUNCE_MS + 1)

        assertEquals(listOf("canvas_open", "a_one"), server.toolsOf(conv1))
        assertEquals(listOf("meridian"), server.toolsOf(meta))
        assertEquals(emptyList<String>(), server.toolsOf(cli))
    }

    private suspend fun DefaultAppServerController.start(scope: AppServerRuntimeScope) {
        startRuntime(AgentId(scope.agentId), ConversationId(scope.conversationId))
    }

    private fun harness(
        fixed: List<HostExternalTool> = listOf(Tool("canvas_open")),
        modes: AgentToolsModePolicy? = null,
        body: suspend TestScope.(FakeToolAppServer, ExternalToolRegistry, DefaultAppServerController) -> Unit,
    ) = runTest {
        val server = FakeToolAppServer()
        val registry = ExternalToolRegistry.hostTools(fixed).let { modes?.apply(it) ?: it }
        var requests = 0
        val controller = DefaultAppServerController(
            client = server,
            requestIdFactory = { "req-${++requests}" },
            externalToolRegistry = registry,
            parentCoroutineContext = StandardTestDispatcher(testScheduler),
        )
        try {
            runCurrent()
            body(server, registry, controller)
        } finally {
            controller.close()
        }
    }

    private class Tool(override val name: String) : HostExternalTool {
        override val description = "test $name"
        override val inputSchema: JsonObject? = null
        override val capability = Capability.ImageHydration
        override suspend fun invoke(input: JsonObject, agentId: String?) = ExternalToolResult.Success(name)
    }

    /** The App Server's runtime_start contract for external tools, as observed on letta-code 0.29.12. */
    private class FakeToolAppServer : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        val runtimeStarts = mutableListOf<AppServerCommand.RuntimeStart>()
        private val toolsByRuntime = mutableMapOf<AppServerRuntimeScope, List<String>>()
        var failNextRuntimeStart = false

        fun toolsOf(scope: AppServerRuntimeScope): List<String> = toolsByRuntime[scope].orEmpty()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
            runtimeStarts += command
            val scope = AppServerRuntimeScope(requireNotNull(command.agentId), requireNotNull(command.conversationId))
            if (failNextRuntimeStart) {
                failNextRuntimeStart = false
                return AppServerInboundFrame.RuntimeStartResponse(requestId = command.requestId, success = false, error = "boom")
            }
            toolsByRuntime[scope] = command.externalTools.orEmpty().flatMap { group -> group.tools.map { it.name } }
            return AppServerInboundFrame.RuntimeStartResponse(requestId = command.requestId, success = true, runtime = scope)
        }

        override suspend fun input(command: AppServerCommand.Input) = Unit

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }
}
