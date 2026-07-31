package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.controller.reconnect.ExternalToolRegistrar
import com.letta.mobile.data.transport.appserver.AppServerExternalToolDefinition
import com.letta.mobile.data.transport.appserver.AppServerExternalToolsGroup
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Registry for controller-owned external tools.
 *
 * This registry:
 * - Holds the set of external tools available for the controller
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
     * All available external tools.
     */
    private val tools: List<ExternalTool>,

    /**
     * The advertised capabilities that gate which tools are registered.
     */
    private val capabilities: RemoteCapabilities,
) : ExternalToolRegistrar {
    /**
     * Tools that are advertised (i.e., their capability is enabled).
     */
    private val advertisedTools: List<ExternalTool> by lazy {
        tools.filter { capabilities.has(it.capability) }
    }

    /**
     * Map of tool name -> tool for fast lookup.
     */
    private val toolsByName: Map<String, ExternalTool> by lazy {
        advertisedTools.associateBy { it.name }
    }

    /**
     * Lists all tools that should be advertised to the App Server.
     *
     * Only includes tools whose capability is enabled in RemoteCapabilities.
     *
     * @return List of advertised tools
     */
    fun listAdvertisedTools(): List<ExternalTool> = advertisedTools

    /**
     * lgns8.17(a): the wire form of [listAdvertisedTools] for the `external_tools`
     * field of `runtime_start`.
     *
     * THIS IS THE ONLY WAY A REQUEST CAN EVER ARRIVE. letta-code's app-server
     * emits `external_tool_call_request` **exclusively** for tools registered by
     * `registerRuntimeExternalTools(...)`, which reads `runtime_start.external_tools`
     * (see `letta.js`: `registerRuntimeExternalTools(context.runtime, connectionId,
     * runtimeScope, parsed.external_tools ?? [])`). A controller that never writes
     * the field therefore never receives a request — and a controller that writes
     * it MUST answer, because the server parks the tool call on a pending promise
     * bounded only by its own `EXTERNAL_TOOL_CALL_TIMEOUT_MS` (5 minutes).
     *
     * Returns null when nothing is advertised so the command omits the field
     * entirely rather than sending an empty group (the server treats an empty
     * group list as "unregister everything", which is the same observable state,
     * but omitting is the smaller, more obviously-correct frame).
     */
    fun advertisedToolsCommandGroups(scopeId: String? = null): List<AppServerExternalToolsGroup>? {
        val definitions = advertisedTools.map { tool ->
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
     * @return The tool result (success or error)
     * @throws ToolNotFoundException if the tool is not found or not advertised
     */
    suspend fun invoke(toolName: String, input: JsonObject): ExternalToolResult {
        val tool = toolsByName[toolName]
            ?: return ExternalToolResult.Error("Tool not found or not advertised: $toolName")

        return try {
            tool.invoke(input)
        } catch (e: Exception) {
            ExternalToolResult.Error("Tool invocation failed: ${e.message}")
        }
    }

    /**
     * Re-registration hook invoked after `runtime_start` on reconnect.
     *
     * Intentionally a no-op. External tools are startup-bound: they are advertised
     * to the App Server via the `external_tools` field of the `runtime_start`
     * command itself, at the moment the controller (re)issues `runtime_start`. On
     * reconnect, [com.letta.mobile.data.controller.reconnect.ReconnectCoordinator]
     * calls `controller.startRuntime(...)` for every active record, which re-issues
     * `runtime_start` and therefore re-advertises the tools as a side effect of that
     * single call — there is no separate "re-advertise tools" frame in the protocol.
     *
     * This registry is a pure definition provider ([listAdvertisedTools]); it holds
     * no transport handle and receives only the [runtime] scope here, so it has no
     * reachable primitive to re-advertise independently. The re-advertisement seam
     * is `runtime_start`, owned by the controller, not this hook. Keeping the hook
     * (rather than deleting the interface) preserves the seam for a future protocol
     * that adds an out-of-band tool-registration frame.
     */
    override suspend fun reRegisterAll(runtime: AppServerRuntimeScope) {
        // No-op by design — see KDoc. Re-advertisement rides on runtime_start,
        // which the reconnect coordinator already re-issues per active runtime.
    }

    companion object {
        /**
         * Creates a registry with the standard set of extra tools.
         *
         * @param capabilities The advertised capabilities that gate which tools are registered
         * @return A registry with all standard extra tools
         */
        fun standard(capabilities: RemoteCapabilities): ExternalToolRegistry {
            return ExternalToolRegistry(
                tools = listOf(
                    ImageHydrationTool(),
                    GoalsTool(),
                    SchedulesTool(),
                    SlashCommandsTool(),
                    SubagentChipsTool(),
                    ReflectionTool(),
                    SlimAgentsTool(),
                ),
                capabilities = capabilities,
            )
        }

        /**
         * Creates a factory-default registry, which advertises NO external tools.
         *
         * lgns8.17(a) — WHY ADVERTISING NOTHING IS THE CORRECT PRODUCTION DEFAULT,
         * not an oversight:
         *
         * 1. `external_tools` is an OPT-IN EXTENSION, not a requirement. letta-code
         *    runs its own native tool loop for its built-in tools (Bash, Read, Edit,
         *    …) on the app-server route; `external_tool_call_request` is emitted
         *    ONLY for names the controller itself registered through
         *    `runtime_start.external_tools`. Advertising nothing means the server
         *    can never emit a request, so nothing can go unanswered.
         * 2. Every tool in [standard] ([ImageHydrationTool], [GoalsTool],
         *    [SchedulesTool], [SlashCommandsTool], [SubagentChipsTool],
         *    [ReflectionTool], [SlimAgentsTool]) is an UNIMPLEMENTED STUB whose
         *    `invoke` returns `ExternalToolResult.Error("… is not yet implemented")`.
         *    Advertising them would inject always-failing tools into the model's
         *    tool list — strictly worse than not offering them, because the model
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
         * with no further wiring — and the engine's answer guarantee already covers
         * the request it will then receive.
         *
         * @return A registry with no advertised tools
         */
        fun factoryDefault(): ExternalToolRegistry {
            return standard(RemoteCapabilities.FACTORY_DEFAULT)
        }

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
