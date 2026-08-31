package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.Capability
import kotlinx.serialization.json.JsonObject

/**
 * External tool abstraction for App Server controller-owned tools.
 *
 * External tools are startup-bound on runtime_start and registered by the controller
 * to extend the App Server's capabilities. Each tool corresponds to a RemoteCapabilities
 * flag and is only registered when that capability is advertised.
 *
 * PROTOCOL:
 * - Tools are registered via runtime_start (external_tools field)
 * - App Server sends ExternalToolCallRequest when a tool is invoked
 * - Controller responds with ExternalToolCallResponse (result or error)
 *
 * USAGE:
 * ```kotlin
 * val tool = ImageHydrationTool()
 * val result = tool.invoke(inputArgs)
 * ```
 */
interface ExternalTool {
    /**
     * The tool's unique name (used in tool_name field of ExternalToolCallRequest).
     */
    val name: String

    /**
     * Human-readable description of what this tool does.
     */
    val description: String

    /**
     * JSON schema for the tool's input parameters.
     *
     * This schema is sent to the App Server during registration and defines
     * the structure of the `input` field in ExternalToolCallRequest.
     *
     * If null, the tool accepts no parameters.
     */
    val inputSchema: JsonObject?

    /**
     * Which RemoteCapabilities flag advertises this tool.
     *
     * The tool is only registered when this capability is enabled.
     */
    val capability: Capability

    /**
     * Invokes the tool with the given input arguments.
     *
     * @param input The input arguments from the ExternalToolCallRequest
     * @param agentId The id of the agent invoking this tool, when known (derived
     *   from the `runtime` field of the inbound request). Tools that don't need
     *   agent context ignore it. Default `null` preserves the pre-letting
     *   agent-context contract for the existing extra tools (image_hydration,
     *   goals, etc.) which never needed to know the caller.
     * @return The tool result (success or error)
     */
    suspend fun invoke(input: JsonObject, agentId: String? = null): ExternalToolResult
}

/**
 * Host-owned external tool that is available because of the client device, not
 * because the remote App Server advertised an extension capability.
 */
interface HostExternalTool : ExternalTool

/**
 * Result of an external tool invocation.
 */
sealed interface ExternalToolResult {
    /**
     * Tool invocation succeeded.
     *
     * @param content The result content (text, JSON, etc.)
     */
    data class Success(val content: String) : ExternalToolResult

    /**
     * Tool invocation failed.
     *
     * @param error The error message
     */
    data class Error(val error: String) : ExternalToolResult
}
