package com.letta.mobile.data.meridian

import com.letta.mobile.data.canvas.CanvasPreviewRender
import com.letta.mobile.data.canvas.CanvasPreviewRenderer
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasSceneSchema
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasTools
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.controller.extras.AgentDiscoveryTool
import com.letta.mobile.data.controller.extras.CustomIrohMessagingTool
import com.letta.mobile.data.controller.extras.DiscoverableAgent
import com.letta.mobile.data.controller.extras.ExternalTool
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.controller.extras.ToolSource
import com.letta.mobile.data.plugin.PluginActionInvoker
import com.letta.mobile.data.plugin.PluginActionTool
import com.letta.mobile.data.plugin.PluginToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A host with every kind of tool the Meridian surface must reach (letta-mobile-jna0o.3): the Iroh
 * host's canvas tools (with a preview renderer), agent_discover, agent_message_send, a plugin
 * action from a live source, and device_action (the Android app's tool, reached as `tool <name>`).
 * Every call any tool receives is recorded, so a test can prove which registry entry ran and for
 * whom.
 */
internal class MeridianTestHost(withCanvas: Boolean = true) {
    data class Call(val tool: String, val input: JsonObject, val caller: ExternalToolCaller)

    val calls = mutableListOf<Call>()

    private val store = InMemoryCanvasRelayStore()
    private val backend = HostCanvasBackend(CanvasRelayHost(store, hostId = { "host-1" }), store, InMemoryHostCanvasDirectory())
    private val renderer = CanvasPreviewRenderer { _, _ -> CanvasPreviewRender("png", emptyList(), emptyList()) }

    private val canvasTools: List<HostExternalTool> =
        if (withCanvas) HostCanvasTools.all(backend, renderer).map { Recording(it, calls) } else emptyList()

    private val discovery = AgentDiscoveryTool {
        listOf(DiscoverableAgent(agentId = "agent-bob", name = "Bob", role = "reviewer"))
    }

    val registry: ExternalToolRegistry = ExternalToolRegistry.standard(
        capabilities = RemoteCapabilities(agentMessaging = true),
        agentDiscoveryTool = null,
        hostTools = canvasTools + listOf(
            Recording(HostAdapter(discovery), calls),
            Recording(FakeMessageTool(), calls),
            Recording(FakeDeviceAction(), calls),
        ),
    ).also { registry ->
        val plugin = PluginActionTool(PLUGIN_DEFINITION, PluginActionInvoker { definition, input, caller ->
            calls += Call(definition.name, input, caller)
            ExternalToolResult.Success("""{"ok":true,"action":"${definition.action}"}""")
        })
        registry.addSource(ToolSource.static("plugins", listOf(plugin)))
    }

    fun router(
        limits: MeridianLimits = MeridianLimits(),
        rateLimiter: MeridianRateLimiter = MeridianRateLimiter.Unlimited,
        inputFiles: MeridianInputFiles? = null,
    ) = MeridianCommandRouter(registry, limits, rateLimiter, inputFiles)

    /** Records each call, then answers as [delegate] does. */
    private class Recording(private val delegate: ExternalTool, private val calls: MutableList<Call>) : HostExternalTool {
        override val name: String get() = delegate.name
        override val description: String get() = delegate.description
        override val inputSchema: JsonObject? get() = delegate.inputSchema
        override val capability: Capability get() = delegate.capability

        override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
            invoke(input, ExternalToolCaller(agentId))

        override suspend fun invoke(input: JsonObject, caller: ExternalToolCaller): ExternalToolResult {
            calls += Call(name, input, caller)
            return delegate.invoke(input, caller)
        }
    }

    /** agent_discover is capability-gated; as a host tool it is advertised here without a binary. */
    private class HostAdapter(private val tool: ExternalTool) : HostExternalTool, ExternalTool by tool

    /** agent_message_send without the `meridian` binary: same name and schema, a canned answer. */
    private class FakeMessageTool : HostExternalTool {
        private val real = CustomIrohMessagingTool(binary = "meridian")
        override val name: String = CustomIrohMessagingTool.TOOL_NAME
        override val description: String get() = real.description
        override val inputSchema: JsonObject get() = real.inputSchema
        override val capability: Capability = Capability.AgentMessaging

        override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
            ExternalToolResult.Success("""{"ok":true,"from":"$agentId"}""")
    }

    private class FakeDeviceAction : HostExternalTool {
        override val name: String = "device_action"
        override val description: String = "Run a device action. Call with action device.catalog for the list."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"action":{"type":"string"},"args":{"type":"object"}},"required":["action"]}""",
        ).jsonObject
        override val capability: Capability = Capability.ImageHydration

        override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
            ExternalToolResult.Success("""{"ok":true}""")
    }

    companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-meridian"
        const val TOOL_CALL = "toolu_meridian_1"
        val CALLER = ExternalToolCaller(AGENT, CONVERSATION, TOOL_CALL)

        val PLUGIN_DEFINITION = PluginToolDefinition(
            pluginId = "letta.example",
            action = "start",
            name = "example_start",
            description = "Example: Start a job and place a widget",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"label":{"type":"string"}},"additionalProperties":false}""",
            ).jsonObject,
        )

        val SHAPE_OP: String get() = """{"type":"add_element","elementId":"box-1","elementJson":${CanvasSceneSchema.shape.example}}"""
    }
}
