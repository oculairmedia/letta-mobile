package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.controller.reconnect.ExternalToolRegistrar
import com.letta.mobile.data.transport.appserver.AppServerExternalToolDefinition
import com.letta.mobile.data.transport.appserver.AppServerExternalToolsGroup
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Registry for controller-owned external tools.
 *
 * This registry:
 * - Holds the fixed set of external tools the host was built with (an immutable fast path)
 * - Holds the live [ToolSource]s added at runtime (letta-mobile-s416w.27), whose tools join the
 *   advertised set while the source is present
 * - Filters tools based on advertised RemoteCapabilities
 * - Routes inbound ExternalToolCallRequest to the appropriate tool
 * - Implements ExternalToolRegistrar for reconnect support
 *
 * USAGE:
 * ```kotlin
 * val registry = ExternalToolRegistry(
 *     tools = listOf(ImageHydrationTool(), GoalsTool(), ...),
 *     capabilities = RemoteCapabilities(imageHydration = true, goals = true)
 * )
 * registry.addSource(pluginSource)          // advertised from the agent's next turn
 *
 * // List tools to advertise
 * val advertised = registry.listAdvertisedTools()
 *
 * // Route an inbound call
 * val result = registry.invoke("image_hydration", inputArgs)
 * ```
 */
class ExternalToolRegistry(
    /**
     * The fixed external tools: advertised for the registry's whole life.
     */
    private val tools: List<ExternalTool>,

    /**
     * The advertised capabilities that gate which tools are registered.
     */
    private val capabilities: RemoteCapabilities,
) : ExternalToolRegistrar {
    /**
     * Fixed tools that are advertised (i.e., their capability is enabled).
     */
    private val advertisedTools: List<ExternalTool> by lazy {
        tools.filter(::isAdvertisable)
    }

    /**
     * Map of tool name -> fixed tool for fast lookup.
     */
    private val toolsByName: Map<String, ExternalTool> by lazy {
        advertisedTools.associateBy { it.name }
    }

    /** The live sources; empty for every registry that never had one added. */
    private val dynamicTools = DynamicToolSet(
        fixedNames = { toolsByName.keys },
        isAdvertisable = ::isAdvertisable,
    )

    /**
     * Emits the advertised set after every [addSource] / [removeSource], and when a source
     * publishes a list with different definitions. Never emits for a registry that has only fixed
     * tools, so such a host behaves exactly as before. The controller re-advertises on it.
     */
    val toolsChanged: Flow<List<ExternalTool>> = dynamicTools.changes { listAdvertisedTools() }

    private fun isAdvertisable(tool: ExternalTool): Boolean =
        tool is HostExternalTool || capabilities.has(tool.capability)

    /**
     * Adds a live tool source. Its tools are advertised from the next `runtime_start` (the
     * controller re-issues one per active runtime on [toolsChanged]). A tool whose name is already
     * taken, by a fixed tool or an earlier source, is not advertised.
     *
     * @return false when a source with the same [ToolSource.id] is already present
     */
    fun addSource(source: ToolSource): Boolean = dynamicTools.add(source)

    /**
     * Removes the live source with [sourceId]. A call to one of its tools that arrives afterwards
     * (an agent's in-flight turn still lists it) is answered with "no longer available".
     *
     * @return false when no such source was present
     */
    fun removeSource(sourceId: String): Boolean = dynamicTools.remove(sourceId)

    /**
     * Lists all tools that should be advertised to the App Server: the fixed tools whose
     * capability is enabled, then the live sources' tools.
     *
     * @return List of advertised tools
     */
    fun listAdvertisedTools(): List<ExternalTool> {
        val dynamic = dynamicTools.current()
        return if (dynamic.isEmpty()) advertisedTools else advertisedTools + dynamic
    }

    /**
     * lgns8.17(a): the wire form of [listAdvertisedTools] for the `external_tools`
     * field of `runtime_start`.
     *
     * THIS IS THE ONLY WAY A REQUEST CAN EVER ARRIVE. letta-code's app-server
     * emits `external_tool_call_request` **exclusively** for tools registered by
     * `registerRuntimeExternalTools(...)`, which reads `runtime_start.external_tools`
     * (see `letta.js`: `registerRuntimeExternalTools(context.runtime, connectionId,
     * runtimeScope, parsed.external_tools ?? [])`). A controller that never writes
     * the field therefore never receives a request, and a controller that writes
     * it MUST answer, because the server parks the tool call on a pending promise
     * bounded only by its own `EXTERNAL_TOOL_CALL_TIMEOUT_MS` (5 minutes).
     *
     * letta-mobile-s416w.27: every `runtime_start` REPLACES the runtime's tools (the server
     * unregisters the previous set for that connection and runtime first), so this must always be
     * the whole current set, never a delta.
     *
     * Returns null when nothing is advertised so the command omits the field
     * entirely rather than sending an empty group (the server treats an omitted
     * field and an empty group list alike: "unregister everything").
     */
    fun advertisedToolsCommandGroups(scopeId: String? = null): List<AppServerExternalToolsGroup>? {
        val advertised = listAdvertisedTools()
        dynamicTools.markAdvertised(advertised)
        val definitions = advertised.map { tool ->
            AppServerExternalToolDefinition(
                name = tool.name,
                description = tool.description,
                // The server's ExternalToolDefinitionPayload requires a parameters
                // object; a tool that takes no arguments still needs a valid empty
                // JSON-Schema object, never a missing/null field.
                parameters = tool.inputSchema ?: EMPTY_OBJECT_SCHEMA,
            )
        }
        if (definitions.isEmpty()) return null
        return listOf(AppServerExternalToolsGroup(scopeId = scopeId, tools = definitions))
    }

    /**
     * Invokes a tool by name with the given input arguments.
     *
     * @param toolName The name of the tool to invoke
     * @param input The input arguments for the tool
     * @param agentId The id of the agent invoking this tool, when known (derived
     *   from the inbound request's runtime scope). Tools that need an
     *   `fromAgentId` (e.g. the Iroh agent-message sender) consume this;
     *   others ignore it. Default `null` preserves the pre-agent-context
     *   contract for the existing extras (image_hydration, goals, ...).
     * @param conversationId The conversation the agent's runtime is in, from the same scope.
     * @return The tool result (success or error)
     */
    suspend fun invoke(
        toolName: String,
        input: JsonObject,
        agentId: String? = null,
        conversationId: String? = null,
    ): ExternalToolResult = invoke(toolName, input, ExternalToolCaller(agentId, conversationId))

    /**
     * [invoke] on behalf of [caller]: its agent and conversation, and the request's `tool_call_id`
     * ([ExternalToolCaller.toolCallId], letta-mobile-bglj6.12).
     */
    suspend fun invoke(toolName: String, input: JsonObject, caller: ExternalToolCaller): ExternalToolResult {
        val tool = toolsByName[toolName] ?: dynamicTools.find(toolName)
            ?: return ExternalToolResult.Error(unavailableMessage(toolName))

        return try {
            tool.invoke(input, caller)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ExternalToolResult.Error("Tool invocation failed: ${e.message}")
        }
    }

    private fun unavailableMessage(toolName: String): String =
        if (dynamicTools.wasAdvertised(toolName)) {
            "tool '$toolName' is no longer available"
        } else {
            "Tool not found or not advertised: $toolName"
        }

    /**
     * Re-registration hook invoked after `runtime_start` on reconnect.
     *
     * Intentionally a no-op. External tools are advertised to the App Server via the
     * `external_tools` field of the `runtime_start` command itself, at the moment the
     * controller (re)issues `runtime_start`. On reconnect,
     * [com.letta.mobile.data.controller.reconnect.ReconnectCoordinator] calls
     * `controller.startRuntime(...)` for every active record, which re-issues
     * `runtime_start` and therefore re-advertises the tools as a side effect of that
     * single call; there is no separate "re-advertise tools" frame in the protocol.
     *
     * letta-mobile-s416w.27: a change to the live sources is re-advertised the same way. The
     * registry raises [toolsChanged] and the controller re-issues `runtime_start` for each active
     * runtime (letta-code 0.29.12 replaces a live runtime's tools on a repeated `runtime_start`;
     * see docs/architecture/live-external-tools.md). This hook has no transport handle, so it
     * stays a no-op.
     */
    override suspend fun reRegisterAll(runtime: AppServerRuntimeScope) {
        // No-op by design; see KDoc. Re-advertisement rides on runtime_start.
    }

    companion object {
        /**
         * Creates a registry with the standard set of extra tools.
         *
         * @param capabilities The advertised capabilities that gate which tools are registered
         * @param customIrohMessagingTool Optional
         *   [CustomIrohMessagingTool] to include when [RemoteCapabilities.agentMessaging]
         *   is enabled. The wrapper distribution supplies this when it has the
         *   `meridian agent-message send` CLI binary available. Null in
         *   factory-default Android controllers, which have no CLI to call.
         * @return A registry with all standard extra tools (and the Iroh
         *   tool, when its capability is enabled AND a tool instance was
         *   supplied).
         */
        fun standard(
            capabilities: RemoteCapabilities,
            customIrohMessagingTool: CustomIrohMessagingTool? = null,
            agentDiscoveryTool: AgentDiscoveryTool? = null,
            hostTools: List<HostExternalTool> = emptyList(),
        ): ExternalToolRegistry {
            val baseTools = listOf(
                ImageHydrationTool(),
                GoalsTool(),
                SchedulesTool(),
                SlashCommandsTool(),
                SubagentChipsTool(),
                ReflectionTool(),
                SlimAgentsTool(),
            )
            // The Iroh tool is only added to the candidate list when both the
            // capability is enabled AND the controller supplied an instance.
            // The registry filters by capability at lookup time; the candidate
            // list is the superset of "tools we know how to advertise".
            val toolsWithIroh = buildList {
                addAll(baseTools)
                if (customIrohMessagingTool != null) add(customIrohMessagingTool)
                if (agentDiscoveryTool != null) add(agentDiscoveryTool)
                addAll(hostTools)
            }
            return ExternalToolRegistry(
                tools = toolsWithIroh,
                capabilities = capabilities,
            )
        }

        /**
         * Creates a factory-default registry, which advertises NO external tools.
         *
         * lgns8.17(a): WHY ADVERTISING NOTHING IS THE CORRECT PRODUCTION DEFAULT,
         * not an oversight:
         *
         * 1. `external_tools` is an OPT-IN EXTENSION, not a requirement. letta-code
         *    runs its own native tool loop for its built-in tools (Bash, Read, Edit,
         *    ...) on the app-server route; `external_tool_call_request` is emitted
         *    ONLY for names the controller itself registered through
         *    `runtime_start.external_tools`. Advertising nothing means the server
         *    can never emit a request, so nothing can go unanswered.
         * 2. Every tool in [standard] ([ImageHydrationTool], [GoalsTool],
         *    [SchedulesTool], [SlashCommandsTool], [SubagentChipsTool],
         *    [ReflectionTool], [SlimAgentsTool]) is an UNIMPLEMENTED STUB whose
         *    `invoke` returns `ExternalToolResult.Error("... is not yet implemented")`.
         *    Advertising them would inject always-failing tools into the model's
         *    tool list, strictly worse than not offering them, because the model
         *    would select them and burn turns on guaranteed errors. They are gated
         *    behind [RemoteCapabilities] precisely so an extended (Meridian)
         *    deployment can light them up once they are real.
         * 3. lettashim parity: the shim never handled `external_tool_call_request`
         *    either. Its extra tools were passed to the Letta SDK as the `tools`
         *    argument on the internal route (`admin-shim/lib/letta-sdk-adapter.ts`),
         *    a different mechanism entirely. So "advertises none over WS" IS the
         *    behaviour being superseded, not a regression against it.
         *
         * The advertisement PLUMBING is nonetheless live and exercised
         * ([advertisedToolsCommandGroups] is wired into `runtime_start` in both
         * `DefaultAppServerController` and `AppServerTurnEngine`): the moment a
         * capability is enabled and a real tool replaces a stub, it is advertised
         * with no further wiring, and the engine's answer guarantee already covers
         * the request it will then receive.
         *
         * @return A registry with no advertised tools
         */
        fun factoryDefault(): ExternalToolRegistry {
            return standard(RemoteCapabilities.FACTORY_DEFAULT)
        }

        /** Creates a baseline-safe registry containing only tools supplied by this host. */
        fun hostTools(tools: List<HostExternalTool>): ExternalToolRegistry =
            standard(RemoteCapabilities.FACTORY_DEFAULT, hostTools = tools)

        /**
         * JSON-Schema for a tool that takes no arguments. `parameters` is a
         * required field of the server's tool payload, so a null [ExternalTool.inputSchema]
         * must still serialise to a valid empty object schema.
         */
        private val EMPTY_OBJECT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }
    }
}

/**
 * Exception thrown when a tool is not found in the registry.
 */
class ToolNotFoundException(toolName: String) : Exception("Tool not found: $toolName")
