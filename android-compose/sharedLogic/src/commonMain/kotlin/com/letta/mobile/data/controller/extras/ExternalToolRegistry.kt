package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.controller.reconnect.ExternalToolRegistrar
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.serialization.json.JsonObject

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
     * Re-registers all advertised external tools for the given runtime.
     *
     * This method is called after runtime_start on reconnect to restore the
     * external tool definitions.
     *
     * @param runtime The runtime scope to re-register tools for
     */
    override suspend fun reRegisterAll(runtime: AppServerRuntimeScope) {
        // TODO: In a real implementation, this would send the tool definitions
        // to the App Server via runtime_start's external_tools field.
        // For now, this is a no-op since the actual registration happens
        // during runtime_start (which the reconnect coordinator already calls).
        //
        // The registry's role is to provide the tool definitions that the
        // controller uses when building the runtime_start command.
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
         * Creates a factory-default registry with no extra tools.
         *
         * @return A registry with no advertised tools
         */
        fun factoryDefault(): ExternalToolRegistry {
            return standard(RemoteCapabilities.FACTORY_DEFAULT)
        }
    }
}

/**
 * Exception thrown when a tool is not found in the registry.
 */
class ToolNotFoundException(toolName: String) : Exception("Tool not found: $toolName")
